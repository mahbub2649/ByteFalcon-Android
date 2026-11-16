// cleaner.cpp: deletion that can't be tricked into deleting the wrong thing.
//  - The storage root, the scan root and Android's standard folders are refused.
//  - The entry is re-checked against the scan (type, and size + mtime for files).
//  - Recursive delete works relative to open directory fds with O_NOFOLLOW, so a symlink
//    swapped in mid-delete can't redirect it, and it never enters another filesystem.
// Deletes on /storage/emulated go through Android's MediaProvider, which keeps the
// media database (Gallery, Files) in sync by itself.
#include "cleaner.hpp"
#include <cerrno>
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace ds {
namespace {

bool under(const std::string &path, const std::string &dir) {
    return path == dir || (path.size() > dir.size() && path.compare(0, dir.size(), dir) == 0 && path[dir.size()] == '/');
}

// Returns 0 or the first errno; `freed` counts what was actually removed.
int removeAt(int dirfd, const char *name, dev_t dev, uint64_t &freed) {
    struct stat st;
    if (fstatat(dirfd, name, &st, AT_SYMLINK_NOFOLLOW) != 0) return errno;
    if (st.st_dev != dev) return EXDEV;  // a mount point inside: leave it alone
    if (!S_ISDIR(st.st_mode)) {
        if (unlinkat(dirfd, name, 0) != 0) return errno;
        freed += (uint64_t)st.st_blocks * 512;
        return 0;
    }
    int fd = openat(dirfd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return errno;
    std::vector<std::string> names;  // read everything first; deleting while iterating can skip entries
    std::vector<char> buf(32 * 1024);  // heap, not stack: this function recurses once per level
    for (long n; (n = syscall(SYS_getdents64, fd, buf.data(), buf.size())) > 0;) {
        for (long off = 0; off < n;) {
            auto *e = reinterpret_cast<struct dirent64 *>(buf.data() + off);
            off += e->d_reclen;
            if (strcmp(e->d_name, ".") && strcmp(e->d_name, "..")) names.emplace_back(e->d_name);
        }
    }
    int firstErr = 0;
    for (auto &n : names) {
        int e = removeAt(fd, n.c_str(), dev, freed);
        if (e && !firstErr) firstErr = e;
    }
    close(fd);
    if (unlinkat(dirfd, name, AT_REMOVEDIR) != 0) return firstErr ? firstErr : errno;
    freed += (uint64_t)st.st_blocks * 512;
    return firstErr;
}

} // namespace

std::string volumeOf(const std::string &path) {
    if (!under(path, "/storage")) return "";
    // /storage/emulated/<user> for internal storage, /storage/<uuid> for SD cards and USB drives.
    size_t parts = under(path, "/storage/emulated") ? 3 : 2, end = 0;
    for (size_t i = 0; i < parts && end != std::string::npos; i++) end = path.find('/', end + 1);
    return end == std::string::npos ? path : path.substr(0, end);
}

std::string protectedReason(const std::string &path, const std::string &storageRoot, const std::string &scanRoot) {
    if (path.empty() || path[0] != '/') return "not an absolute path";
    if (path == storageRoot || path == scanRoot) return "refusing to delete a top-level folder";
    if (!under(path, "/storage")) return "outside shared storage";  // scan roots are realpath()ed, so never /sdcard
    std::string volume = volumeOf(path);
    if (volume == path) return "refusing to delete a storage volume";
    static const char *kKeep[] = {"/Android", "/Android/data", "/Android/media", "/Android/obb", "/DCIM", "/DCIM/Camera",
                                  "/Download", "/Documents", "/Movies", "/Music", "/Pictures", "/Alarms", "/Notifications",
                                  "/Ringtones", "/Podcasts", "/Recordings", "/Audiobooks"};
    for (const char *k : kKeep)
        if (path == volume + k) return "important folder; delete what's inside it instead";
    return "";
}

CleanResult cleanEntry(const std::string &path, const Node &rec) {
    CleanResult r;
    struct stat st;
    if (lstat(path.c_str(), &st) != 0) {
        r.gone = errno == ENOENT;
        r.error = r.gone ? "already gone" : strerror(errno);
        return r;
    }
    bool typeOk = rec.type == T_DIR ? S_ISDIR(st.st_mode)
                : rec.type == T_FILE ? S_ISREG(st.st_mode)
                : rec.type == T_LINK ? S_ISLNK(st.st_mode)
                                     : !S_ISDIR(st.st_mode) && !S_ISREG(st.st_mode);
    if (!typeOk || (rec.type == T_FILE && ((uint64_t)st.st_size != rec.bytes || st.st_mtime != rec.mtime))) {
        r.error = "changed since the scan; rescan first";
        return r;
    }
    size_t slash = path.rfind('/');
    std::string parent = slash == 0 ? "/" : path.substr(0, slash), name = path.substr(slash + 1);
    int dfd = open(parent.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dfd < 0) {
        r.error = strerror(errno);
        return r;
    }
    int e = removeAt(dfd, name.c_str(), st.st_dev, r.freed);
    close(dfd);
    r.ok = e == 0;
    if (!r.ok) r.error = std::string(e == EXDEV ? "contains another mounted drive" : strerror(e)) +
                         (r.freed ? " (partly deleted; rescan to refresh)" : "");
    return r;
}

void detachNode(Tree &t, uint32_t id) {
    Node &n = t.nodes[id];
    uint64_t size = n.size, bytes = n.bytes;
    uint32_t files = n.type == T_DIR ? n.files : (n.type == T_FILE ? 1 : 0);
    if (n.parent != kNone) {
        uint32_t *link = &t.nodes[n.parent].firstChild;
        while (*link != kNone && *link != id) link = &t.nodes[*link].nextSibling;
        if (*link == id) *link = n.nextSibling;
    }
    for (uint32_t a = n.parent; a != kNone; a = t.nodes[a].parent) {
        Node &p = t.nodes[a];
        p.size -= std::min(p.size, size);
        p.bytes -= std::min(p.bytes, bytes);
        p.files -= std::min(p.files, files);
    }
    t.files -= std::min<uint64_t>(t.files, files);
    std::vector<uint32_t> stack{id};
    while (!stack.empty()) {
        uint32_t c = stack.back();
        stack.pop_back();
        t.nodes[c].flags |= F_DELETED;
        for (uint32_t k = t.nodes[c].firstChild; k != kNone; k = t.nodes[k].nextSibling) stack.push_back(k);
    }
}

} // namespace ds
