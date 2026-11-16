// engine.cpp: scan, analyse and clean, exposed as JSON endpoints:
//   info, scan, progress, cancel, summary, node, tree, largest, old, types, smart, path,
//   dupes/start, dupes, clean
// Long work (scan, duplicate search) runs on background threads; the UI polls progress.
#include "engine.hpp"
#include "core/cleaner.hpp"
#include "core/dupes.hpp"
#include "core/json.hpp"
#include "core/scanner.hpp"
#include "core/smart.hpp"
#include <ctime>
#include <queue>
#include <shared_mutex>
#include <sys/statvfs.h>
#include <thread>
#include <unordered_map>

namespace ds {
namespace {

const char *typeName(uint8_t t) { return t == T_DIR ? "d" : t == T_FILE ? "f" : t == T_LINK ? "l" : "o"; }

uint64_t toU64(const std::string &s, uint64_t def) {
    if (s.empty()) return def;
    char *e;
    unsigned long long v = strtoull(s.c_str(), &e, 10);
    return *e ? def : v;
}

std::string param(const std::map<std::string, std::string> &q, const char *k) {
    auto it = q.find(k);
    return it == q.end() ? "" : it->second;
}

std::vector<uint32_t> sortedChildren(const Tree &t, uint32_t id) {
    std::vector<uint32_t> v;
    t.forEachChild(id, [&](uint32_t c) { v.push_back(c); });
    std::sort(v.begin(), v.end(), [&](uint32_t a, uint32_t b) { return t.nodes[a].size > t.nodes[b].size; });
    return v;
}

void nodeFields(JsonWriter &w, const Tree &t, uint32_t id) {
    const Node &n = t.nodes[id];
    w.kv("id", id).kv("name", n.name).kv("type", typeName(n.type)).kv("size", n.size).kv("bytes", n.bytes).kv("mtime", n.mtime);
    if (n.type == T_DIR) w.kv("files", n.files);
    if (n.flags & F_MOUNT) w.kv("mount", true);
    if (n.flags & F_ERROR) w.kv("unreadable", true);
}

void writeTree(JsonWriter &w, const Tree &t, uint32_t id, int depth, size_t limit, size_t subLimit) {
    w.beginObj();
    nodeFields(w, t, id);
    if (depth > 0 && t.nodes[id].type == T_DIR) {
        auto kids = sortedChildren(t, id);
        size_t n = std::min(kids.size(), limit);
        uint64_t rest = 0;
        for (size_t i = n; i < kids.size(); i++) rest += t.nodes[kids[i]].size;
        w.kv("restSize", rest).kv("restCount", (uint64_t)(kids.size() - n)).key("children").beginArr();
        for (size_t i = 0; i < n; i++) writeTree(w, t, kids[i], depth - 1, subLimit, subLimit);
        w.endArr();
    }
    w.endObj();
}

std::string extOf(const char *name) {
    const char *dot = strrchr(name, '.');
    if (!dot || dot == name || !dot[1] || strlen(dot + 1) > 8) return "";
    std::string e(dot + 1);
    for (char &c : e) c = (char)tolower((unsigned char)c);
    return e;
}

EngineResult ok(const JsonWriter &w) { return {200, w.str()}; }
EngineResult fail(int status, const std::string &msg) {
    JsonWriter w;
    w.beginObj().kv("error", msg).endObj();
    return {status, w.str()};
}

class Engine {
public:
    void init(std::vector<Volume> v) { volumes_ = std::move(v); }

    EngineResult call(const std::string &method, const std::string &ep, const std::map<std::string, std::string> &q,
                      const std::string &rawBody) {
        Json body;
        if (!rawBody.empty() && !Json::parse(rawBody, body)) return fail(400, "bad json");
        bool post = method == "POST";
        if (ep == "info") return info();
        if (ep == "progress") return progress();
        if (ep == "scan" && post) return scan(body);
        if (ep == "cancel" && post) {
            std::lock_guard<std::mutex> pl(progMu_);
            if (progress_) progress_->cancel = true;
            return ok(JsonWriter().beginObj().kv("ok", true).endObj());
        }
        if (ep == "dupes/start" && post) return dupesStart(body);
        if (ep == "dupes") return dupesStatus();
        if (ep == "clean" && post) return clean(body);

        std::shared_lock<std::shared_mutex> lk(treeMu_);
        if (!tree_) return fail(409, "no scan yet");
        const Tree &t = *tree_;
        if (ep == "summary") return summary(t);
        if (ep == "node") return node(t, q);
        if (ep == "tree") return tree(t, q);
        if (ep == "largest") return topFiles(t, q, false);
        if (ep == "old") return topFiles(t, q, true);
        if (ep == "types") return types(t);
        if (ep == "smart") return smart(t);
        if (ep == "path") {  // absolute path, so the app can hand the file to a viewer
            uint32_t id = (uint32_t)toU64(param(q, "id"), kNone);
            if (!t.valid(id)) return fail(404, "no longer exists");
            JsonWriter w;
            w.beginObj().kv("path", t.path(id)).kv("type", typeName(t.nodes[id].type)).endObj();
            return ok(w);
        }
        return fail(404, "unknown endpoint " + ep);
    }

private:
    std::string volumeRootOf(const std::string &path) const {
        std::string v = volumeOf(path);
        return v.empty() ? path : v;
    }
    std::string display(const std::string &path) const {
        for (const Volume &v : volumes_) {
            if (path == v.path) return v.label;
            if (path.size() > v.path.size() && path.compare(0, v.path.size(), v.path) == 0 && path[v.path.size()] == '/')
                return v.label + path.substr(v.path.size());
        }
        return path;
    }

    std::shared_ptr<DupeJob> dupes() {
        std::lock_guard<std::mutex> jl(jobMu_);
        return dupes_;
    }
    void stopDupesLocked() {
        if (dupes_) dupes_->cancel = true;
        if (dupeThread_.joinable()) dupeThread_.join();
        dupes_.reset();
    }

    EngineResult info() {
        JsonWriter w;
        w.beginObj().kv("threads", defaultScanThreads()).key("volumes").beginArr();
        for (const Volume &v : volumes_) {
            struct statvfs vs {};
            if (statvfs(v.path.c_str(), &vs) != 0) continue;
            w.beginObj().kv("path", v.path).kv("label", v.label).kv("total", (uint64_t)vs.f_blocks * vs.f_frsize)
                .kv("free", (uint64_t)vs.f_bavail * vs.f_frsize).endObj();
        }
        w.endArr().endObj();
        return ok(w);
    }

    EngineResult scan(const Json &body) {
        std::string path = body["path"].str(volumes_.empty() ? "/storage/emulated/0" : volumes_[0].path);
        std::lock_guard<std::mutex> cl(cleanMu_);  // never swap the tree under a running cleanup
        std::lock_guard<std::mutex> jl(jobMu_);
        if (scanning_) return fail(409, "a scan is already running");
        stopDupesLocked();
        if (scanThread_.joinable()) scanThread_.join();
        auto prog = std::make_shared<ScanProgress>();
        prog->running = true;
        {
            std::lock_guard<std::mutex> pl(progMu_);
            progress_ = prog;
        }
        scanning_ = true;
        ScanOptions opt{path, (unsigned)body["threads"].num(0), false};
        scanThread_ = std::thread([this, prog, opt] {
            auto t = scanTree(opt, *prog);
            if (t) {
                std::unique_lock<std::shared_mutex> tl(treeMu_);
                tree_ = std::move(t);
            }
            prog->running = false;
            scanning_ = false;
        });
        return ok(JsonWriter().beginObj().kv("ok", true).endObj());
    }

    EngineResult progress() {
        std::shared_ptr<ScanProgress> prog;
        {
            std::lock_guard<std::mutex> pl(progMu_);
            prog = progress_;
        }
        bool ready;
        {
            std::shared_lock<std::shared_mutex> lk(treeMu_);
            ready = tree_ != nullptr;
        }
        JsonWriter w;
        w.beginObj().kv("ready", ready).kv("running", prog && prog->running.load());
        if (prog) {
            std::lock_guard<std::mutex> l(prog->mu);
            w.kv("files", prog->files.load()).kv("dirs", prog->dirs.load()).kv("bytes", prog->bytes.load())
                .kv("errors", prog->errors.load()).kv("seconds", prog->seconds()).kv("current", display(prog->current))
                .kv("error", prog->error);
        }
        w.endObj();
        return ok(w);
    }

    EngineResult summary(const Tree &t) {
        const Node &r = t.nodes[t.root];
        struct statvfs vs {};
        statvfs(t.rootPath.c_str(), &vs);
        JsonWriter w;
        w.beginObj().kv("root", t.rootPath).kv("display", display(t.rootPath)).kv("rootId", t.root).kv("size", r.size)
            .kv("bytes", r.bytes).kv("files", t.files).kv("dirs", t.dirs).kv("errors", t.errors).kv("seconds", t.seconds)
            .kv("threads", t.threads).kv("diskTotal", (uint64_t)vs.f_blocks * vs.f_frsize)
            .kv("diskFree", (uint64_t)vs.f_bavail * vs.f_frsize).key("top").beginArr();
        auto kids = sortedChildren(t, t.root);
        for (size_t i = 0; i < kids.size() && i < 10; i++) {
            w.beginObj();
            nodeFields(w, t, kids[i]);
            w.endObj();
        }
        w.endArr().endObj();
        return ok(w);
    }

    EngineResult node(const Tree &t, const std::map<std::string, std::string> &q) {
        uint32_t id = (uint32_t)toU64(param(q, "id"), t.root);
        if (!t.valid(id)) id = t.root;
        if (t.nodes[id].type != T_DIR) id = t.nodes[id].parent;
        size_t limit = std::clamp<uint64_t>(toU64(param(q, "limit"), 400), 1, 5000);
        auto kids = sortedChildren(t, id);
        JsonWriter w;
        w.beginObj().key("node").beginObj();
        nodeFields(w, t, id);
        w.kv("path", display(t.path(id))).endObj().key("crumbs").beginArr();
        std::vector<uint32_t> chain;
        for (uint32_t c = id; c != kNone; c = t.nodes[c].parent) chain.push_back(c);
        for (size_t i = chain.size(); i-- > 0;)
            w.beginObj().kv("id", chain[i]).kv("name", chain[i] == t.root ? display(t.rootPath) : std::string(t.nodes[chain[i]].name)).endObj();
        w.endArr().key("children").beginArr();
        size_t n = std::min(kids.size(), limit);
        uint64_t rest = 0;
        for (size_t i = 0; i < kids.size(); i++) {
            if (i >= n) { rest += t.nodes[kids[i]].size; continue; }
            w.beginObj();
            nodeFields(w, t, kids[i]);
            w.endObj();
        }
        w.endArr().kv("restCount", (uint64_t)(kids.size() - n)).kv("restSize", rest).endObj();
        return ok(w);
    }

    EngineResult tree(const Tree &t, const std::map<std::string, std::string> &q) {
        uint32_t id = (uint32_t)toU64(param(q, "id"), t.root);
        if (!t.valid(id) || t.nodes[id].type != T_DIR) id = t.root;
        JsonWriter w;
        writeTree(w, t, id, 2, std::clamp<uint64_t>(toU64(param(q, "limit"), 30), 1, 200), 12);
        return ok(w);
    }

    EngineResult topFiles(const Tree &t, const std::map<std::string, std::string> &q, bool old) {
        size_t limit = std::clamp<uint64_t>(toU64(param(q, "limit"), 300), 1, 3000);
        int64_t cutoff = old ? time(nullptr) - (int64_t)toU64(param(q, "days"), 365) * 86400 : INT64_MAX;
        uint64_t minSize = old ? toU64(param(q, "min"), 10'000'000) : 1;
        using P = std::pair<uint64_t, uint32_t>;
        std::priority_queue<P, std::vector<P>, std::greater<P>> heap;
        uint64_t count = 0, total = 0;
        for (uint32_t i = 0, n = t.nodes.size(); i < n; i++) {
            const Node &x = t.nodes[i];
            if (!x.name || x.type != T_FILE || (x.flags & F_DELETED) || x.size < minSize || x.mtime > cutoff) continue;
            count++;
            total += x.size;
            if (heap.size() < limit) heap.push({x.size, i});
            else if (x.size > heap.top().first) { heap.pop(); heap.push({x.size, i}); }
        }
        std::vector<uint32_t> ids;
        for (; !heap.empty(); heap.pop()) ids.push_back(heap.top().second);
        std::reverse(ids.begin(), ids.end());
        JsonWriter w;
        w.beginObj().kv("count", count).kv("total", total).key("items").beginArr();
        for (uint32_t id : ids) {
            w.beginObj();
            nodeFields(w, t, id);
            w.kv("dir", display(t.path(t.nodes[id].parent))).endObj();
        }
        w.endArr().endObj();
        return ok(w);
    }

    EngineResult types(const Tree &t) {
        std::unordered_map<std::string, std::pair<uint64_t, uint64_t>> m;
        for (uint32_t i = 0, n = t.nodes.size(); i < n; i++) {
            const Node &x = t.nodes[i];
            if (!x.name || x.type != T_FILE || (x.flags & F_DELETED)) continue;
            auto &e = m[extOf(x.name)];
            e.first += x.size;
            e.second++;
        }
        std::vector<std::pair<std::string, std::pair<uint64_t, uint64_t>>> v(m.begin(), m.end());
        std::sort(v.begin(), v.end(), [](auto &a, auto &b) { return a.second.first > b.second.first; });
        JsonWriter w;
        w.beginObj().key("types").beginArr();
        for (size_t i = 0; i < v.size() && i < 60; i++)
            w.beginObj().kv("ext", v[i].first).kv("size", v[i].second.first).kv("count", v[i].second.second).endObj();
        w.endArr().endObj();
        return ok(w);
    }

    EngineResult smart(const Tree &t) {
        auto cats = analyzeSmart(t, volumeRootOf(t.rootPath));
        uint64_t safe = 0;
        for (auto &c : cats)
            if (c.safety == "safe") safe += c.total;
        JsonWriter w;
        w.beginObj().kv("safeTotal", safe).key("categories").beginArr();
        for (auto &c : cats) {
            w.beginObj().kv("id", c.id).kv("title", c.title).kv("safety", c.safety).kv("description", c.description)
                .kv("total", c.total).kv("count", (uint64_t)c.items.size()).key("items").beginArr();
            for (size_t i = 0; i < c.items.size() && i < 300; i++) {
                w.beginObj();
                nodeFields(w, t, c.items[i]);
                w.kv("path", display(t.path(c.items[i]))).endObj();
            }
            w.endArr().endObj();
        }
        w.endArr().endObj();
        return ok(w);
    }

    EngineResult dupesStart(const Json &body) {
        uint64_t minSize = std::max<uint64_t>(4096, (uint64_t)body["minSize"].num(1 << 20));
        std::lock_guard<std::mutex> jl(jobMu_);
        if (scanning_) return fail(409, "wait for the scan to finish");
        stopDupesLocked();
        std::vector<DupeCandidate> cands;
        {
            std::shared_lock<std::shared_mutex> lk(treeMu_);
            if (!tree_) return fail(409, "no scan yet");
            const Tree &t = *tree_;
            for (uint32_t i = 0, n = t.nodes.size(); i < n; i++) {
                const Node &x = t.nodes[i];
                if (x.name && x.type == T_FILE && !(x.flags & (F_DELETED | F_HARDLINK)) && x.bytes >= minSize)
                    cands.push_back({i, x.bytes, t.path(i)});
            }
        }
        auto job = std::make_shared<DupeJob>();
        job->state = 1;
        dupes_ = job;
        dupeThread_ = std::thread([job, c = std::move(cands)]() mutable { findDuplicates(std::move(c), *job, 4); });
        return ok(JsonWriter().beginObj().kv("state", 1).endObj());
    }

    EngineResult dupesStatus() {
        auto job = dupes();
        JsonWriter w;
        w.beginObj().kv("state", job ? job->state.load() : 0);
        if (job) {
            std::lock_guard<std::mutex> l(job->mu);
            w.kv("stage", job->stage).kv("filesTotal", job->filesTotal.load()).kv("filesDone", job->filesDone.load())
                .kv("bytesTotal", job->bytesTotal.load()).kv("bytesDone", job->bytesDone.load());
            if (job->state == 2) {
                std::shared_lock<std::shared_mutex> lk(treeMu_);
                uint64_t wasted = 0, shown = 0, groups = 0;
                w.key("groups").beginArr();
                for (auto &g : job->groups) {
                    std::vector<uint32_t> live;
                    for (uint32_t id : g.ids)
                        if (tree_ && tree_->valid(id)) live.push_back(id);
                    if (live.size() < 2) continue;
                    groups++;
                    wasted += g.bytes * (live.size() - 1);
                    if (shown++ >= 200) continue;
                    w.beginObj().kv("bytes", g.bytes).kv("wasted", g.bytes * (live.size() - 1)).key("files").beginArr();
                    for (uint32_t id : live) {
                        w.beginObj();
                        nodeFields(w, *tree_, id);
                        w.kv("dir", display(tree_->path(tree_->nodes[id].parent))).endObj();
                    }
                    w.endArr().endObj();
                }
                w.endArr().kv("groupCount", groups).kv("wastedTotal", wasted);
            }
        }
        w.endObj();
        return ok(w);
    }

    EngineResult clean(const Json &body) {
        std::lock_guard<std::mutex> cl(cleanMu_);
        if (scanning_) return fail(409, "wait for the scan to finish");
        uint64_t freed = 0;
        std::string rootPath;
        JsonWriter w;
        w.beginObj().key("results").beginArr();
        for (const Json &v : body["ids"].a) {
            uint32_t id = (uint32_t)v.num(-1);
            std::string path, reason;
            Node rec;
            {
                std::shared_lock<std::shared_mutex> lk(treeMu_);
                if (!tree_ || !tree_->valid(id)) {
                    w.beginObj().kv("id", id).kv("ok", true).kv("error", "already removed").endObj();
                    continue;
                }
                path = tree_->path(id);
                rec = tree_->nodes[id];
                rootPath = tree_->rootPath;
                reason = protectedReason(path, volumeRootOf(rootPath), rootPath);
            }
            CleanResult r;
            if (reason.empty()) r = cleanEntry(path, rec);
            else r.error = reason;
            if (r.ok || r.gone) {
                std::unique_lock<std::shared_mutex> lk(treeMu_);
                if (tree_ && tree_->valid(id)) detachNode(*tree_, id);
            }
            freed += r.freed;
            w.beginObj().kv("id", id).kv("ok", r.ok || r.gone).kv("path", display(path)).kv("freed", r.freed)
                .kv("error", r.error).endObj();
        }
        struct statvfs vs {};
        statvfs(rootPath.empty() ? "/storage/emulated/0" : rootPath.c_str(), &vs);
        w.endArr().kv("freed", freed).kv("diskFree", (uint64_t)vs.f_bavail * vs.f_frsize).endObj();
        return ok(w);
    }

    std::vector<Volume> volumes_;
    std::shared_mutex treeMu_;  // guards tree_ and its contents
    std::unique_ptr<Tree> tree_;
    std::mutex progMu_, jobMu_, cleanMu_;  // lock order: cleanMu_ -> jobMu_ -> treeMu_
    std::shared_ptr<ScanProgress> progress_;
    std::atomic<bool> scanning_{false};
    std::thread scanThread_, dupeThread_;
    std::shared_ptr<DupeJob> dupes_;
};

Engine &engine() {
    static Engine *e = new Engine();  // lives as long as the process; never destroyed mid-scan
    return *e;
}

} // namespace

void engineInit(std::vector<Volume> volumes) { engine().init(std::move(volumes)); }

EngineResult engineCall(const std::string &method, const std::string &endpoint,
                        const std::map<std::string, std::string> &query, const std::string &body) {
    return engine().call(method, endpoint, query, body);
}

} // namespace ds
