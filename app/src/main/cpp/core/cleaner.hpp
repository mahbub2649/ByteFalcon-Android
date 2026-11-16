// cleaner.hpp: safe deletion on Android shared storage.
#pragma once
#include "tree.hpp"

namespace ds {

struct CleanResult {
    bool ok = false;
    bool gone = false;   // was already missing on disk; the tree entry should be dropped
    uint64_t freed = 0;  // bytes actually released
    std::string error;
};

// Storage volume a path lives on (/storage/emulated/0, /storage/ABCD-1234), or "" outside /storage.
std::string volumeOf(const std::string &path);

// Empty if the path may be deleted, otherwise the reason it is protected.
std::string protectedReason(const std::string &path, const std::string &storageRoot, const std::string &scanRoot);

// Re-checks that the entry on disk still matches the scan, then deletes it.
// Never follows symlinks and never crosses into another mounted filesystem.
CleanResult cleanEntry(const std::string &path, const Node &recorded);

// Drops a deleted entry from the tree and subtracts its size from every ancestor.
void detachNode(Tree &t, uint32_t id);

} // namespace ds
