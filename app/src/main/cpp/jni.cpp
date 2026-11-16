// jni.cpp: the only bridge between Kotlin (app.diskscan.Engine) and the native engine.
// Strings cross as UTF-8 byte arrays, not jstring: NewStringUTF() aborts the process on
// emoji and on invalid UTF-8, and phones are full of file names with both.
#include "engine.hpp"
#include <jni.h>

namespace {

std::string bytes(JNIEnv *env, jbyteArray a) {
    if (!a) return "";
    jsize n = env->GetArrayLength(a);
    std::string s(n, '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

std::map<std::string, std::string> parseQuery(const std::string &q) {
    std::map<std::string, std::string> m;
    for (size_t pos = 0; pos < q.size();) {
        size_t amp = q.find('&', pos);
        if (amp == std::string::npos) amp = q.size();
        std::string kv = q.substr(pos, amp - pos);
        size_t eq = kv.find('=');
        if (!kv.empty()) m[kv.substr(0, eq)] = eq == std::string::npos ? "" : kv.substr(eq + 1);
        pos = amp + 1;
    }
    return m;
}

} // namespace

extern "C" JNIEXPORT void JNICALL Java_app_diskscan_Engine_nativeInit(JNIEnv *env, jclass, jobjectArray volumes) {
    std::vector<ds::Volume> v;
    for (jsize i = 0, n = env->GetArrayLength(volumes); i < n; i++) {
        auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(volumes, i));
        std::string s = bytes(env, item);  // "path\tlabel"
        env->DeleteLocalRef(item);
        size_t tab = s.find('\t');
        v.push_back({s.substr(0, tab), tab == std::string::npos ? s : s.substr(tab + 1)});
    }
    ds::engineInit(std::move(v));
}

// Returns "<status>\n<json body>" as UTF-8 bytes.
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_diskscan_Engine_nativeCall(JNIEnv *env, jclass, jbyteArray method,
                                                                          jbyteArray endpoint, jbyteArray query,
                                                                          jbyteArray body) {
    ds::EngineResult r = ds::engineCall(bytes(env, method), bytes(env, endpoint), parseQuery(bytes(env, query)), bytes(env, body));
    std::string out = std::to_string(r.status) + "\n" + r.body;
    jbyteArray a = env->NewByteArray((jsize)out.size());
    env->SetByteArrayRegion(a, 0, (jsize)out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return a;
}
