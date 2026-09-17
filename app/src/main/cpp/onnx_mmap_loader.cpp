#include "onnx_mmap_loader.hpp"
#include <sys/stat.h>
#include <android/log.h>

#define TAG "iTantraMmap"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace itantra {

MmapModelLoader::MmapModelLoader() {}

MmapModelLoader::~MmapModelLoader() {
    release();
}

bool MmapModelLoader::loadModelFile(const std::string& filePath) {
    release();

    fileDescriptor_ = open(filePath.c_str(), O_RDONLY);
    if (fileDescriptor_ < 0) {
        LOGE("Failed to open model file for mmap: %s", filePath.c_str());
        return false;
    }

    struct stat sb;
    if (fstat(fileDescriptor_, &sb) == -1) {
        LOGE("Failed to get model file status: %s", filePath.c_str());
        close(fileDescriptor_);
        fileDescriptor_ = -1;
        return false;
    }

    fileSize_ = static_cast<size_t>(sb.st_size);

    // Map file with MAP_SHARED and PROT_READ (zero heap allocation)
    mappedData_ = mmap(nullptr, fileSize_, PROT_READ, MAP_SHARED, fileDescriptor_, 0);
    if (mappedData_ == MAP_FAILED) {
        LOGE("mmap failed for file: %s", filePath.c_str());
        close(fileDescriptor_);
        fileDescriptor_ = -1;
        mappedData_ = nullptr;
        fileSize_ = 0;
        return false;
    }

    // Advise OS that we will access data sequentially to optimize page cache
    madvise(mappedData_, fileSize_, MADV_WILLNEED);

    LOGI("Successfully memory-mapped model (%zu bytes) from %s", fileSize_, filePath.c_str());
    return true;
}

void MmapModelLoader::release() {
    if (mappedData_ != nullptr && mappedData_ != MAP_FAILED) {
        munmap(mappedData_, fileSize_);
        mappedData_ = nullptr;
    }

    if (fileDescriptor_ >= 0) {
        close(fileDescriptor_);
        fileDescriptor_ = -1;
    }

    fileSize_ = 0;
}

} // namespace itantra
