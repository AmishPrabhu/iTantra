#pragma once

#include <string>
#include <cstddef>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>

namespace itantra {

/**
 * Memory-Mapped (mmap) Model Weight Loader.
 * Maps ONNX model weights directly from flash storage into virtual address space
 * without loading the whole model into JVM/heap. Prevents Android LowMemoryKiller crashes.
 */
class MmapModelLoader {
public:
    MmapModelLoader();
    ~MmapModelLoader();

    bool loadModelFile(const std::string& filePath);
    void release();

    const void* getMappedData() const { return mappedData_; }
    size_t getFileSize() const { return fileSize_; }
    bool isLoaded() const { return mappedData_ != nullptr && mappedData_ != MAP_FAILED; }

private:
    int fileDescriptor_{-1};
    void* mappedData_{nullptr};
    size_t fileSize_{0};
};

} // namespace itantra
