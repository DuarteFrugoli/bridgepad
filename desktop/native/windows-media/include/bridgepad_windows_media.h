#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define BP_MEDIA_EXPORT __declspec(dllexport)
#else
#define BP_MEDIA_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

enum bp_media_status {
    BP_MEDIA_OK = 0,
    BP_MEDIA_INVALID_ARGUMENT = 1,
    BP_MEDIA_COM_INITIALIZATION_FAILED = 2,
    BP_MEDIA_FOUNDATION_STARTUP_FAILED = 3,
    BP_MEDIA_ENUMERATION_FAILED = 4,
    BP_MEDIA_HARDWARE_ENCODER_UNAVAILABLE = 5,
    BP_MEDIA_ENCODER_ACTIVATION_FAILED = 6,
    BP_MEDIA_NO_OUTPUT = 7,
    BP_MEDIA_D3D_FAILED = 8,
    BP_MEDIA_ENCODER_CONFIGURATION_FAILED = 9,
    BP_MEDIA_ENCODE_FAILED = 10,
    BP_MEDIA_TIMEOUT = 11,
};

typedef struct bp_media_h264_capabilities {
    uint32_t abi_version;
    uint32_t hardware_encoder_count;
    uint8_t selected_encoder_is_async;
    uint8_t selected_encoder_is_d3d11_aware;
    uint8_t reserved[2];
    char selected_encoder_name[256];
} bp_media_h264_capabilities;

typedef struct bp_media_encoder_config {
    uint32_t width;
    uint32_t height;
    uint32_t frames_per_second;
    uint32_t bitrate_bits_per_second;
    uint32_t keyframe_interval_frames;
} bp_media_encoder_config;

typedef struct bp_media_encoded_frame {
    uint8_t* data;
    size_t data_length;
    int64_t timestamp_100ns;
    uint8_t keyframe;
    uint8_t reserved[7];
} bp_media_encoded_frame;

typedef struct bp_media_encoder bp_media_encoder;

BP_MEDIA_EXPORT int32_t bp_media_probe_h264_hardware(
    bp_media_h264_capabilities* capabilities,
    char* error_message,
    size_t error_message_capacity);

BP_MEDIA_EXPORT int32_t bp_media_encoder_create(
    const bp_media_encoder_config* config,
    void* d3d11_device,
    bp_media_encoder** encoder,
    char* error_message,
    size_t error_message_capacity);

BP_MEDIA_EXPORT int32_t bp_media_encoder_encode_texture(
    bp_media_encoder* encoder,
    void* d3d11_texture_2d,
    int64_t timestamp_100ns,
    int64_t duration_100ns,
    bp_media_encoded_frame* encoded_frame,
    char* error_message,
    size_t error_message_capacity);

BP_MEDIA_EXPORT int32_t bp_media_encoder_set_bitrate(
    bp_media_encoder* encoder,
    uint32_t bitrate_bits_per_second,
    char* error_message,
    size_t error_message_capacity);

BP_MEDIA_EXPORT int32_t bp_media_encoder_request_keyframe(
    bp_media_encoder* encoder,
    char* error_message,
    size_t error_message_capacity);

BP_MEDIA_EXPORT void bp_media_encoded_frame_release(bp_media_encoded_frame* frame);
BP_MEDIA_EXPORT void bp_media_encoder_destroy(bp_media_encoder* encoder);

#ifdef __cplusplus
}
#endif
