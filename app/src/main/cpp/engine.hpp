// engine.hpp: the app's whole native API. Kotlin calls it through one JNI function with an
// endpoint name and JSON, and gets JSON back, so the JNI boundary stays one function wide.
#pragma once
#include <map>
#include <string>
#include <utility>
#include <vector>

namespace ds {

struct EngineResult {
    int status = 200;
    std::string body;
};

struct Volume {
    std::string path, label;
};

void engineInit(std::vector<Volume> volumes);
EngineResult engineCall(const std::string &method, const std::string &endpoint,
                        const std::map<std::string, std::string> &query, const std::string &body);

} // namespace ds
