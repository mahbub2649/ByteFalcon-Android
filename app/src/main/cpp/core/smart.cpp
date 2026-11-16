// smart.cpp: finds space on a phone that is usually safe to reclaim. One depth-first walk;
// when a folder matches a rule it is "claimed" and not descended into, so nothing is
// counted twice.
#include "smart.hpp"
#include <ctime>
#include <unordered_map>

namespace ds {
namespace {

struct CatDef { const char *id, *title, *safety, *description; };

const CatDef kCats[] = {
    {"trash", "Trash", "safe", "Files deleted from Gallery or Files. Android keeps them for 30 days; deleting now frees the space."},
    {"thumbs", "Thumbnail caches", "safe", "Preview images the gallery and file apps generate. Rebuilt automatically when needed."},
    {"mediacache", "App caches in shared storage", "safe", "Cache folders apps keep under Android/media. Rebuilt automatically."},
    {"partial", "Unfinished downloads", "safe", "Leftovers (.crdownload, .part, .pending-*) from interrupted downloads and saves."},
    {"installers", "APKs & archives in Download", "review", "App installers and archives you have probably already installed or extracted."},
    {"olddownloads", "Large, old downloads", "review", "Files over 50 MB in Download, unchanged for 90+ days."},
    {"whatsapp", "WhatsApp media", "review", "Photos, videos, voice notes and documents from chats. Check before deleting."},
    {"telegram", "Telegram media", "review", "Files Telegram saved from chats. They can be downloaded again from the chat."},
    {"screenshots", "Old screenshots", "review", "Screenshots older than 90 days."},
    {"logs", "Large logs & crash dumps", "review", "Log files over 20 MB, rotated logs and core dumps."},
};

enum class Claim { Self, Children };

bool endsWith(const std::string &s, const char *suf) {
    size_t n = strlen(suf);
    return s.size() >= n && s.compare(s.size() - n, n, suf) == 0;
}

bool startsWith(const std::string &s, const std::string &p) { return s.compare(0, p.size(), p) == 0; }

std::string lower(const char *s) {
    std::string r(s);
    for (char &c : r) c = (char)tolower((unsigned char)c);
    return r;
}

class Analyzer {
public:
    Analyzer(const Tree &t, const std::string &root) : t_(t), root_(root), now_(time(nullptr)) {
        for (const CatDef &d : kCats) {
            index_[d.id] = cats_.size();
            cats_.push_back({d.id, d.title, d.safety, d.description, {}, 0});
        }
        auto R = [&](const char *rel) { return root_ + rel; };
        exact_ = {
            {R("/Android/media/com.whatsapp/WhatsApp/Media"), {"whatsapp", Claim::Children}},
            {R("/Android/media/com.whatsapp.w4b/WhatsApp Business/Media"), {"whatsapp", Claim::Children}},
            {R("/WhatsApp/Media"), {"whatsapp", Claim::Children}},
            {R("/Android/media/org.telegram.messenger/Telegram"), {"telegram", Claim::Children}},
            {R("/Telegram"), {"telegram", Claim::Children}},
        };
        rootSlash_ = root_ + "/";
        media_ = root_ + "/Android/media/";
        download_ = root_ + "/Download/";
        shots_ = {root_ + "/DCIM/Screenshots/", root_ + "/Pictures/Screenshots/"};
    }

    std::vector<SmartCategory> run() {
        std::string path = t_.rootPath;
        walk(t_.root, path);
        std::vector<SmartCategory> out;
        for (auto &c : cats_) {
            if (c.items.empty()) continue;
            std::sort(c.items.begin(), c.items.end(), [&](uint32_t a, uint32_t b) { return t_.nodes[a].size > t_.nodes[b].size; });
            out.push_back(std::move(c));
        }
        std::stable_sort(out.begin(), out.end(), [](auto &a, auto &b) {
            return a.safety != b.safety ? a.safety == "safe" : a.total > b.total;
        });
        return out;
    }

private:
    void add(const char *cat, uint32_t id) {
        if (t_.nodes[id].size == 0) return;
        SmartCategory &c = cats_[index_[cat]];
        c.items.push_back(id);
        c.total += t_.nodes[id].size;
    }

    void walk(uint32_t id, std::string &path) {
        const Node &n = t_.nodes[id];
        if (n.type != T_DIR) {
            if (n.type == T_FILE) fileRule(id, path);
            return;
        }
        if (id != t_.root && dirRule(id, path)) return;
        size_t len = path.size();
        t_.forEachChild(id, [&](uint32_t c) {
            if (path.back() != '/') path += '/';
            path += t_.nodes[c].name;
            walk(c, path);
            path.resize(len);
        });
    }

    bool dirRule(uint32_t id, const std::string &path) {
        auto it = exact_.find(path);
        if (it != exact_.end()) {
            if (it->second.second == Claim::Self) add(it->second.first, id);
            else t_.forEachChild(id, [&](uint32_t c) { add(it->second.first, c); });
            return true;
        }
        const char *name = t_.nodes[id].name;
        if (!strcmp(name, ".thumbnails") || !strcmp(name, ".Thumbnails")) { add("thumbs", id); return true; }
        if ((!strcmp(name, "cache") || !strcmp(name, ".cache")) && startsWith(path, media_)) { add("mediacache", id); return true; }
        return false;
    }

    void fileRule(uint32_t id, const std::string &path) {
        const Node &n = t_.nodes[id];
        if (!startsWith(path, rootSlash_)) return;
        std::string name = lower(n.name);
        int64_t age = now_ - n.mtime;
        if (startsWith(name, ".trashed-")) { add("trash", id); return; }
        if (endsWith(name, ".crdownload") || endsWith(name, ".part") || endsWith(name, ".partial") ||
            (startsWith(name, ".pending-") && age > 86400)) {
            add("partial", id);
            return;
        }
        if (startsWith(path, download_)) {
            static const char *kInstall[] = {".apk", ".apks", ".xapk", ".zip", ".7z", ".rar", ".tar", ".tgz", ".gz", ".xz", ".iso"};
            for (const char *e : kInstall)
                if (endsWith(name, e)) { add("installers", id); return; }
            if (n.size > 50'000'000 && age > 90 * 86400) { add("olddownloads", id); return; }
        }
        for (const std::string &s : shots_)
            if (startsWith(path, s) && age > 90 * 86400) { add("screenshots", id); return; }
        bool core = name == "core" || (startsWith(name, "core.") && name.size() > 5 &&
                                       name.find_first_not_of("0123456789", 5) == std::string::npos);
        bool rotated = name.find(".log.") != std::string::npos || endsWith(name, ".log.old");
        if (core || rotated || (endsWith(name, ".log") && n.size > 20'000'000)) add("logs", id);
    }

    const Tree &t_;
    std::string root_, rootSlash_, media_, download_;
    std::vector<std::string> shots_;
    int64_t now_;
    std::vector<SmartCategory> cats_;
    std::unordered_map<std::string, size_t> index_;
    std::unordered_map<std::string, std::pair<const char *, Claim>> exact_;
};

} // namespace

std::vector<SmartCategory> analyzeSmart(const Tree &t, const std::string &storageRoot) { return Analyzer(t, storageRoot).run(); }

} // namespace ds
