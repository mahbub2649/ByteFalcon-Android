// smart.hpp: rule-based cleanup suggestions for Android shared storage.
#pragma once
#include "tree.hpp"

namespace ds {

struct SmartCategory {
    std::string id, title, safety, description;  // safety: "safe" | "review"
    std::vector<uint32_t> items;                 // biggest first
    uint64_t total = 0;
};

// storageRoot is the volume the scan belongs to, e.g. /storage/emulated/0.
std::vector<SmartCategory> analyzeSmart(const Tree &t, const std::string &storageRoot);

} // namespace ds
