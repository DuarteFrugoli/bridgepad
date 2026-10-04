#include "bridgepad_windows_media.h"

#if defined(_WIN32)

#define NOMINMAX
#include <Windows.h>
#include <codecapi.h>
#include <d3d11.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <strmif.h>
#include <wrl/client.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace {

using Microsoft::WRL::ComPtr;
constexpr uint32_t kAbiVersion = 1;
constexpr auto kEventTimeout = std::chrono::milliseconds(250);
constexpr HRESULT kBridgePadTimeout = HRESULT_FROM_WIN32(ERROR_TIMEOUT);

void write_error(char* target, size_t capacity, const std::string& message) {
    if (target == nullptr || capacity == 0) return;
    const size_t length = (std::min)(capacity - 1, message.size());
    std::memcpy(target, message.data(), length);
    target[length] = '\0';
}

std::string format_hresult(const char* operation, HRESULT result) {
    char buffer[160]{};
    std::snprintf(buffer, sizeof(buffer), "%s failed with HRESULT 0x%08lX",
                  operation, static_cast<unsigned long>(result));
    return buffer;
}

std::string utf8_from_wide(const wchar_t* value) {
    if (value == nullptr || value[0] == L'\0') return {};
    const int required = WideCharToMultiByte(CP_UTF8, 0, value, -1, nullptr, 0, nullptr, nullptr);
    if (required <= 1) return {};
    std::string result(static_cast<size_t>(required), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value, -1, result.data(), required, nullptr, nullptr);
    result.pop_back();
    return result;
}

class ScopedCom final {
public:
    ScopedCom() noexcept {
        result_ = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        owns_ = SUCCEEDED(result_);
        if (result_ == RPC_E_CHANGED_MODE) result_ = S_OK;
    }
    ~ScopedCom() { if (owns_) CoUninitialize(); }
    HRESULT result() const noexcept { return result_; }
private:
    HRESULT result_ = E_FAIL;
    bool owns_ = false;
};

class ScopedMediaFoundation final {
public:
    ScopedMediaFoundation() noexcept : result_(MFStartup(MF_VERSION, MFSTARTUP_FULL)) {}
    ~ScopedMediaFoundation() { if (SUCCEEDED(result_)) MFShutdown(); }
    HRESULT result() const noexcept { return result_; }
private:
    HRESULT result_ = E_FAIL;
};

struct ActivateArray final {
    IMFActivate** values = nullptr;
    UINT32 count = 0;
    ~ActivateArray() {
        if (values == nullptr) return;
        for (UINT32 index = 0; index < count; ++index) {
            if (values[index] != nullptr) values[index]->Release();
        }
        CoTaskMemFree(values);
    }
};

bool read_uint32(IMFAttributes* attributes, REFGUID key) {
    if (attributes == nullptr) return false;
    UINT32 value = 0;
    return SUCCEEDED(attributes->GetUINT32(key, &value)) && value != 0;
}

HRESULT enumerate_hardware_encoders(ActivateArray& encoders) {
    const MFT_REGISTER_TYPE_INFO input_type{MFMediaType_Video, MFVideoFormat_NV12};
    const MFT_REGISTER_TYPE_INFO output_type{MFMediaType_Video, MFVideoFormat_H264};
    return MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER,
                     MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER,
                     &input_type, &output_type, &encoders.values, &encoders.count);
}

HRESULT set_codec_u32(ICodecAPI* codec, const GUID& key, ULONG value) {
    if (codec == nullptr) return E_NOINTERFACE;
    VARIANT setting;
    VariantInit(&setting);
    setting.vt = VT_UI4;
    setting.ulVal = value;
    const HRESULT result = codec->SetValue(&key, &setting);
    VariantClear(&setting);
    return result;
}

bool has_annex_b_start_code(const std::vector<uint8_t>& bytes) {
    return bytes.size() >= 4 && bytes[0] == 0 && bytes[1] == 0 &&
           (bytes[2] == 1 || (bytes[2] == 0 && bytes[3] == 1));
}

bool convert_avcc_to_annex_b(std::vector<uint8_t>& bytes) {
    if (has_annex_b_start_code(bytes)) return true;
    std::vector<uint8_t> converted;
    size_t offset = 0;
    while (offset + 4 <= bytes.size()) {
        const uint32_t length = (static_cast<uint32_t>(bytes[offset]) << 24U) |
            (static_cast<uint32_t>(bytes[offset + 1]) << 16U) |
            (static_cast<uint32_t>(bytes[offset + 2]) << 8U) |
            static_cast<uint32_t>(bytes[offset + 3]);
        offset += 4;
        if (length == 0 || offset + length > bytes.size()) return false;
        converted.insert(converted.end(), {0, 0, 0, 1});
        converted.insert(converted.end(), bytes.begin() + static_cast<ptrdiff_t>(offset),
                         bytes.begin() + static_cast<ptrdiff_t>(offset + length));
        offset += length;
    }
    if (offset != bytes.size() || converted.empty()) return false;
    bytes = std::move(converted);
    return true;
}

class NativeEncoder final {
public:
    NativeEncoder() = default;
    NativeEncoder(const NativeEncoder&) = delete;
    NativeEncoder& operator=(const NativeEncoder&) = delete;
    ~NativeEncoder() {
        if (transform_ != nullptr) {
            transform_->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
            transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
        }
        if (activation_ != nullptr) activation_->ShutdownObject();
    }

    HRESULT initialize(const bp_media_encoder_config& config,
                       ID3D11Device* borrowed_device, std::string& error) {
        config_ = config;
        device_ = borrowed_device;
        device_->GetImmediateContext(&context_);
        if (context_ == nullptr) {
            error = "ID3D11Device::GetImmediateContext returned null";
            return E_FAIL;
        }
        HRESULT result = device_.As(&video_device_);
        if (FAILED(result)) {
            error = format_hresult("query ID3D11VideoDevice", result);
            return result;
        }
        result = context_.As(&video_context_);
        if (FAILED(result)) {
            error = format_hresult("query ID3D11VideoContext", result);
            return result;
        }
        UINT reset_token = 0;
        result = MFCreateDXGIDeviceManager(&reset_token, &device_manager_);
        if (FAILED(result)) {
            error = format_hresult("MFCreateDXGIDeviceManager", result);
            return result;
        }
        result = device_manager_->ResetDevice(device_.Get(), reset_token);
        if (FAILED(result)) {
            error = format_hresult("IMFDXGIDeviceManager::ResetDevice", result);
            return result;
        }

        ActivateArray encoders;
        result = enumerate_hardware_encoders(encoders);
        if (FAILED(result)) {
            error = format_hresult("MFTEnumEx", result);
            return result;
        }
        if (encoders.count == 0) {
            error = "Windows reported no hardware H.264 encoder accepting NV12";
            return MF_E_TOPO_CODEC_NOT_FOUND;
        }
        HRESULT last_error = E_FAIL;
        for (UINT32 index = 0; index < encoders.count; ++index) {
            ComPtr<IMFTransform> candidate;
            result = encoders.values[index]->ActivateObject(IID_PPV_ARGS(&candidate));
            if (FAILED(result)) { last_error = result; continue; }
            ComPtr<IMFAttributes> attributes;
            candidate->GetAttributes(&attributes);
            if (!read_uint32(attributes.Get(), MF_TRANSFORM_ASYNC) ||
                !read_uint32(attributes.Get(), MF_SA_D3D11_AWARE)) {
                encoders.values[index]->ShutdownObject();
                last_error = MF_E_INVALIDMEDIATYPE;
                continue;
            }
            result = attributes->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE);
            if (FAILED(result)) {
                encoders.values[index]->ShutdownObject();
                last_error = result;
                continue;
            }
            activation_ = encoders.values[index];
            transform_ = std::move(candidate);
            break;
        }
        if (transform_ == nullptr) {
            error = format_hresult("activate asynchronous D3D11-aware H.264 MFT", last_error);
            return last_error;
        }
        result = transform_.As(&events_);
        if (FAILED(result)) {
            error = format_hresult("query IMFMediaEventGenerator", result);
            return result;
        }
        transform_.As(&codec_api_);
        result = transform_->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER,
                                             reinterpret_cast<ULONG_PTR>(device_manager_.Get()));
        if (FAILED(result)) {
            error = format_hresult("MFT_MESSAGE_SET_D3D_MANAGER", result);
            return result;
        }
        result = configure_media_types(error);
        if (FAILED(result)) return result;

        set_codec_u32(codec_api_.Get(), CODECAPI_AVLowLatencyMode, TRUE);
        set_codec_u32(codec_api_.Get(), CODECAPI_AVEncCommonRateControlMode,
                      eAVEncCommonRateControlMode_CBR);
        set_codec_u32(codec_api_.Get(), CODECAPI_AVEncCommonMeanBitRate,
                      config_.bitrate_bits_per_second);
        set_codec_u32(codec_api_.Get(), CODECAPI_AVEncMPVGOPSize,
                      config_.keyframe_interval_frames);
        result = transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
        if (SUCCEEDED(result)) {
            result = transform_->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
        }
        if (FAILED(result)) error = format_hresult("start H.264 MFT stream", result);
        return result;
    }

    HRESULT encode(ID3D11Texture2D* source_texture, int64_t timestamp_100ns,
                   int64_t duration_100ns, std::vector<uint8_t>& bytes,
                   int64_t& output_timestamp, bool& keyframe, std::string& error) {
        HRESULT result = convert_to_nv12(source_texture, error);
        if (FAILED(result)) return result;
        result = needs_input_ ? S_OK : wait_for_event(METransformNeedInput, error);
        if (FAILED(result)) return result;

        ComPtr<IMFMediaBuffer> input_buffer;
        result = MFCreateDXGISurfaceBuffer(__uuidof(ID3D11Texture2D),
                                           nv12_texture_.Get(), 0, FALSE, &input_buffer);
        if (FAILED(result)) {
            error = format_hresult("MFCreateDXGISurfaceBuffer", result);
            return result;
        }
        ComPtr<IMFSample> input_sample;
        result = MFCreateSample(&input_sample);
        if (SUCCEEDED(result)) result = input_sample->AddBuffer(input_buffer.Get());
        if (SUCCEEDED(result)) result = input_sample->SetSampleTime(timestamp_100ns);
        if (SUCCEEDED(result)) result = input_sample->SetSampleDuration(duration_100ns);
        if (FAILED(result)) {
            error = format_hresult("create Media Foundation input sample", result);
            return result;
        }
        result = transform_->ProcessInput(input_stream_id_, input_sample.Get(), 0);
        if (FAILED(result)) {
            error = format_hresult("IMFTransform::ProcessInput", result);
            return result;
        }
        needs_input_ = false;
        result = wait_for_output_or_more_input(error);
        if (result == S_FALSE) return S_FALSE;
        if (FAILED(result)) return result;
        return read_output(bytes, output_timestamp, keyframe, error);
    }

    HRESULT set_bitrate(uint32_t bitrate, std::string& error) {
        const HRESULT result = set_codec_u32(codec_api_.Get(),
            CODECAPI_AVEncCommonMeanBitRate, bitrate);
        if (FAILED(result)) error = format_hresult("set encoder bitrate", result);
        return result;
    }

    HRESULT request_keyframe(std::string& error) {
        const HRESULT result = set_codec_u32(codec_api_.Get(),
            CODECAPI_AVEncVideoForceKeyFrame, TRUE);
        if (FAILED(result)) error = format_hresult("request keyframe", result);
        return result;
    }

private:
    HRESULT configure_media_types(std::string& error) {
        ComPtr<IMFMediaType> output;
        HRESULT result = MFCreateMediaType(&output);
        if (SUCCEEDED(result)) result = output->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
        if (SUCCEEDED(result)) result = output->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
        if (SUCCEEDED(result)) result = MFSetAttributeSize(output.Get(), MF_MT_FRAME_SIZE,
                                                           config_.width, config_.height);
        if (SUCCEEDED(result)) result = MFSetAttributeRatio(output.Get(), MF_MT_FRAME_RATE,
                                                             config_.frames_per_second, 1);
        if (SUCCEEDED(result)) result = MFSetAttributeRatio(output.Get(),
                                                             MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        if (SUCCEEDED(result)) result = output->SetUINT32(MF_MT_INTERLACE_MODE,
                                                          MFVideoInterlace_Progressive);
        if (SUCCEEDED(result)) result = output->SetUINT32(MF_MT_AVG_BITRATE,
                                                          config_.bitrate_bits_per_second);
        if (SUCCEEDED(result)) result = output->SetUINT32(MF_MT_MPEG2_PROFILE,
                                                          eAVEncH264VProfile_Main);
        if (SUCCEEDED(result)) result = transform_->SetOutputType(output_stream_id_, output.Get(), 0);
        if (FAILED(result)) {
            error = format_hresult("configure H.264 output media type", result);
            return result;
        }

        ComPtr<IMFMediaType> input;
        result = MFCreateMediaType(&input);
        if (SUCCEEDED(result)) result = input->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
        if (SUCCEEDED(result)) result = input->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
        if (SUCCEEDED(result)) result = MFSetAttributeSize(input.Get(), MF_MT_FRAME_SIZE,
                                                           config_.width, config_.height);
        if (SUCCEEDED(result)) result = MFSetAttributeRatio(input.Get(), MF_MT_FRAME_RATE,
                                                             config_.frames_per_second, 1);
        if (SUCCEEDED(result)) result = MFSetAttributeRatio(input.Get(),
                                                             MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        if (SUCCEEDED(result)) result = input->SetUINT32(MF_MT_INTERLACE_MODE,
                                                         MFVideoInterlace_Progressive);
        if (SUCCEEDED(result)) result = transform_->SetInputType(input_stream_id_, input.Get(), 0);
        if (FAILED(result)) error = format_hresult("configure NV12 input media type", result);
        return result;
    }

    HRESULT ensure_video_processor(uint32_t source_width, uint32_t source_height,
                                   std::string& error) {
        if (video_processor_ != nullptr && source_width_ == source_width &&
            source_height_ == source_height) return S_OK;
        source_width_ = source_width;
        source_height_ = source_height;
        video_processor_.Reset();
        video_enumerator_.Reset();
        nv12_output_view_.Reset();
        nv12_texture_.Reset();
        const D3D11_VIDEO_PROCESSOR_CONTENT_DESC content{
            D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE,
            {config_.frames_per_second, 1}, source_width, source_height,
            {config_.frames_per_second, 1}, config_.width, config_.height,
            D3D11_VIDEO_USAGE_PLAYBACK_NORMAL};
        HRESULT result = video_device_->CreateVideoProcessorEnumerator(&content, &video_enumerator_);
        if (SUCCEEDED(result)) {
            result = video_device_->CreateVideoProcessor(video_enumerator_.Get(), 0,
                                                          &video_processor_);
        }
        if (FAILED(result)) {
            error = format_hresult("create D3D11 video processor", result);
            return result;
        }
        D3D11_TEXTURE2D_DESC desc{};
        desc.Width = config_.width;
        desc.Height = config_.height;
        desc.MipLevels = 1;
        desc.ArraySize = 1;
        desc.Format = DXGI_FORMAT_NV12;
        desc.SampleDesc.Count = 1;
        desc.Usage = D3D11_USAGE_DEFAULT;
        desc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
        result = device_->CreateTexture2D(&desc, nullptr, &nv12_texture_);
        if (FAILED(result)) {
            error = format_hresult("create NV12 D3D11 texture", result);
            return result;
        }
        D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC output_desc{};
        output_desc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
        output_desc.Texture2D.MipSlice = 0;
        result = video_device_->CreateVideoProcessorOutputView(nv12_texture_.Get(),
            video_enumerator_.Get(), &output_desc, &nv12_output_view_);
        if (FAILED(result)) error = format_hresult("create NV12 output view", result);
        return result;
    }

    HRESULT convert_to_nv12(ID3D11Texture2D* source_texture, std::string& error) {
        D3D11_TEXTURE2D_DESC source_desc{};
        source_texture->GetDesc(&source_desc);
        HRESULT result = ensure_video_processor(source_desc.Width, source_desc.Height, error);
        if (FAILED(result)) return result;
        D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC input_desc{};
        input_desc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
        input_desc.Texture2D.MipSlice = 0;
        input_desc.Texture2D.ArraySlice = 0;
        ComPtr<ID3D11VideoProcessorInputView> input_view;
        result = video_device_->CreateVideoProcessorInputView(source_texture,
            video_enumerator_.Get(), &input_desc, &input_view);
        if (FAILED(result)) {
            error = format_hresult("create video processor input view", result);
            return result;
        }
        const RECT source_rect{0, 0, static_cast<LONG>(source_desc.Width),
                               static_cast<LONG>(source_desc.Height)};
        const RECT output_rect{0, 0, static_cast<LONG>(config_.width),
                               static_cast<LONG>(config_.height)};
        video_context_->VideoProcessorSetStreamSourceRect(video_processor_.Get(), 0, TRUE,
                                                           &source_rect);
        video_context_->VideoProcessorSetStreamDestRect(video_processor_.Get(), 0, TRUE,
                                                         &output_rect);
        video_context_->VideoProcessorSetOutputTargetRect(video_processor_.Get(), TRUE,
                                                           &output_rect);
        D3D11_VIDEO_PROCESSOR_STREAM stream{};
        stream.Enable = TRUE;
        stream.pInputSurface = input_view.Get();
        result = video_context_->VideoProcessorBlt(video_processor_.Get(),
            nv12_output_view_.Get(), 0, 1, &stream);
        if (FAILED(result)) error = format_hresult("VideoProcessorBlt", result);
        return result;
    }

    HRESULT wait_for_event(MediaEventType wanted, std::string& error) {
        const auto deadline = std::chrono::steady_clock::now() + kEventTimeout;
        while (std::chrono::steady_clock::now() < deadline) {
            ComPtr<IMFMediaEvent> event;
            HRESULT result = events_->GetEvent(MF_EVENT_FLAG_NO_WAIT, &event);
            if (result == MF_E_NO_EVENTS_AVAILABLE) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                continue;
            }
            if (FAILED(result)) {
                error = format_hresult("IMFMediaEventGenerator::GetEvent", result);
                return result;
            }
            HRESULT event_status = S_OK;
            event->GetStatus(&event_status);
            if (FAILED(event_status)) {
                error = format_hresult("asynchronous MFT event", event_status);
                return event_status;
            }
            MediaEventType type = MEUnknown;
            event->GetType(&type);
            if (type == METransformNeedInput) needs_input_ = true;
            if (type == wanted) return S_OK;
        }
        error = wanted == METransformNeedInput
            ? "timed out waiting for METransformNeedInput"
            : "timed out waiting for METransformHaveOutput";
        return kBridgePadTimeout;
    }

    HRESULT wait_for_output_or_more_input(std::string& error) {
        const auto deadline = std::chrono::steady_clock::now() + kEventTimeout;
        while (std::chrono::steady_clock::now() < deadline) {
            ComPtr<IMFMediaEvent> event;
            HRESULT result = events_->GetEvent(MF_EVENT_FLAG_NO_WAIT, &event);
            if (result == MF_E_NO_EVENTS_AVAILABLE) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                continue;
            }
            if (FAILED(result)) {
                error = format_hresult("IMFMediaEventGenerator::GetEvent", result);
                return result;
            }
            HRESULT event_status = S_OK;
            event->GetStatus(&event_status);
            if (FAILED(event_status)) {
                error = format_hresult("asynchronous MFT event", event_status);
                return event_status;
            }
            MediaEventType type = MEUnknown;
            event->GetType(&type);
            if (type == METransformHaveOutput) return S_OK;
            if (type == METransformNeedInput) {
                needs_input_ = true;
                return S_FALSE;
            }
        }
        error = "timed out waiting for an asynchronous H.264 MFT event";
        return kBridgePadTimeout;
    }

    HRESULT read_output(std::vector<uint8_t>& bytes, int64_t& timestamp,
                        bool& keyframe, std::string& error) {
        MFT_OUTPUT_STREAM_INFO info{};
        HRESULT result = transform_->GetOutputStreamInfo(output_stream_id_, &info);
        if (FAILED(result)) {
            error = format_hresult("GetOutputStreamInfo", result);
            return result;
        }
        ComPtr<IMFSample> sample;
        if ((info.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) == 0) {
            result = MFCreateSample(&sample);
            ComPtr<IMFMediaBuffer> buffer;
            if (SUCCEEDED(result)) result = MFCreateMemoryBuffer(info.cbSize, &buffer);
            if (SUCCEEDED(result)) result = sample->AddBuffer(buffer.Get());
            if (FAILED(result)) {
                error = format_hresult("allocate H.264 output sample", result);
                return result;
            }
        }
        MFT_OUTPUT_DATA_BUFFER output{};
        output.dwStreamID = output_stream_id_;
        output.pSample = sample.Get();
        DWORD status = 0;
        result = transform_->ProcessOutput(0, 1, &output, &status);
        if (output.pEvents != nullptr) output.pEvents->Release();
        if (result == MF_E_TRANSFORM_STREAM_CHANGE && !output_stream_changed_) {
            output_stream_changed_ = true;
            result = renegotiate_output(error);
            if (FAILED(result)) return result;
            result = wait_for_event(METransformHaveOutput, error);
            if (FAILED(result)) return result;
            return read_output(bytes, timestamp, keyframe, error);
        }
        if (FAILED(result)) {
            error = format_hresult("IMFTransform::ProcessOutput", result);
            return result;
        }
        if (output.pSample != nullptr && output.pSample != sample.Get()) sample.Attach(output.pSample);
        if (sample == nullptr) {
            error = "H.264 MFT returned no output sample";
            return MF_E_TRANSFORM_NEED_MORE_INPUT;
        }
        ComPtr<IMFMediaBuffer> contiguous;
        result = sample->ConvertToContiguousBuffer(&contiguous);
        BYTE* data = nullptr;
        DWORD length = 0;
        if (SUCCEEDED(result)) result = contiguous->Lock(&data, nullptr, &length);
        if (FAILED(result)) {
            error = format_hresult("read H.264 output buffer", result);
            return result;
        }
        bytes.assign(data, data + length);
        contiguous->Unlock();
        if (!convert_avcc_to_annex_b(bytes)) {
            error = "H.264 MFT returned neither Annex-B nor valid AVCC data";
            return MF_E_INVALID_STREAM_DATA;
        }
        timestamp = 0;
        sample->GetSampleTime(&timestamp);
        UINT32 clean = FALSE;
        sample->GetUINT32(MFSampleExtension_CleanPoint, &clean);
        keyframe = clean != FALSE;
        return S_OK;
    }

    HRESULT renegotiate_output(std::string& error) {
        for (DWORD index = 0;; ++index) {
            ComPtr<IMFMediaType> type;
            HRESULT result = transform_->GetOutputAvailableType(output_stream_id_, index, &type);
            if (result == MF_E_NO_MORE_TYPES) break;
            if (FAILED(result)) {
                error = format_hresult("GetOutputAvailableType after stream change", result);
                return result;
            }
            GUID subtype{};
            if (FAILED(type->GetGUID(MF_MT_SUBTYPE, &subtype)) || subtype != MFVideoFormat_H264) {
                continue;
            }
            MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, config_.width, config_.height);
            MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, config_.frames_per_second, 1);
            type->SetUINT32(MF_MT_AVG_BITRATE, config_.bitrate_bits_per_second);
            result = transform_->SetOutputType(output_stream_id_, type.Get(), 0);
            if (SUCCEEDED(result)) return S_OK;
        }
        error = "H.264 MFT requested a stream change without offering a compatible type";
        return MF_E_INVALIDMEDIATYPE;
    }

    bp_media_encoder_config config_{};
    uint32_t source_width_ = 0;
    uint32_t source_height_ = 0;
    DWORD input_stream_id_ = 0;
    DWORD output_stream_id_ = 0;
    bool needs_input_ = false;
    bool output_stream_changed_ = false;
    ComPtr<ID3D11Device> device_;
    ComPtr<ID3D11DeviceContext> context_;
    ComPtr<ID3D11VideoDevice> video_device_;
    ComPtr<ID3D11VideoContext> video_context_;
    ComPtr<IMFDXGIDeviceManager> device_manager_;
    ComPtr<IMFActivate> activation_;
    ComPtr<IMFTransform> transform_;
    ComPtr<IMFMediaEventGenerator> events_;
    ComPtr<ICodecAPI> codec_api_;
    ComPtr<ID3D11VideoProcessorEnumerator> video_enumerator_;
    ComPtr<ID3D11VideoProcessor> video_processor_;
    ComPtr<ID3D11Texture2D> nv12_texture_;
    ComPtr<ID3D11VideoProcessorOutputView> nv12_output_view_;
};

int32_t status_from_hresult(HRESULT result, int32_t fallback) {
    if (result == MF_E_TOPO_CODEC_NOT_FOUND) return BP_MEDIA_HARDWARE_ENCODER_UNAVAILABLE;
    if (result == kBridgePadTimeout) return BP_MEDIA_TIMEOUT;
    return fallback;
}

}  // namespace

struct bp_media_encoder {
    ScopedCom com;
    ScopedMediaFoundation media_foundation;
    NativeEncoder implementation;
};

extern "C" int32_t bp_media_probe_h264_hardware(
    bp_media_h264_capabilities* capabilities, char* error_message,
    size_t error_message_capacity) {
    if (capabilities == nullptr) {
        write_error(error_message, error_message_capacity, "capabilities must not be null");
        return BP_MEDIA_INVALID_ARGUMENT;
    }
    *capabilities = {};
    capabilities->abi_version = kAbiVersion;
    ScopedCom com;
    if (FAILED(com.result())) {
        write_error(error_message, error_message_capacity,
                    format_hresult("CoInitializeEx", com.result()));
        return BP_MEDIA_COM_INITIALIZATION_FAILED;
    }
    ScopedMediaFoundation mf;
    if (FAILED(mf.result())) {
        write_error(error_message, error_message_capacity, format_hresult("MFStartup", mf.result()));
        return BP_MEDIA_FOUNDATION_STARTUP_FAILED;
    }
    ActivateArray encoders;
    const HRESULT enumeration = enumerate_hardware_encoders(encoders);
    if (FAILED(enumeration)) {
        write_error(error_message, error_message_capacity, format_hresult("MFTEnumEx", enumeration));
        return BP_MEDIA_ENUMERATION_FAILED;
    }
    capabilities->hardware_encoder_count = encoders.count;
    if (encoders.count == 0) {
        write_error(error_message, error_message_capacity,
                    "Windows reported no hardware H.264 encoder accepting NV12");
        return BP_MEDIA_HARDWARE_ENCODER_UNAVAILABLE;
    }
    HRESULT last_error = E_FAIL;
    for (UINT32 index = 0; index < encoders.count; ++index) {
        IMFActivate* activation = encoders.values[index];
        ComPtr<IMFTransform> transform;
        const HRESULT result = activation->ActivateObject(IID_PPV_ARGS(&transform));
        if (FAILED(result)) { last_error = result; continue; }
        wchar_t* wide_name = nullptr;
        UINT32 name_length = 0;
        std::string name = "Unnamed hardware H.264 encoder";
        if (SUCCEEDED(activation->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute,
                                                     &wide_name, &name_length))) {
            const std::string converted = utf8_from_wide(wide_name);
            CoTaskMemFree(wide_name);
            if (!converted.empty()) name = converted;
        }
        ComPtr<IMFAttributes> attributes;
        transform->GetAttributes(&attributes);
        capabilities->selected_encoder_is_async =
            static_cast<uint8_t>(read_uint32(attributes.Get(), MF_TRANSFORM_ASYNC));
        capabilities->selected_encoder_is_d3d11_aware =
            static_cast<uint8_t>(read_uint32(attributes.Get(), MF_SA_D3D11_AWARE));
        const size_t bytes = (std::min)(name.size(),
            sizeof(capabilities->selected_encoder_name) - 1);
        std::memcpy(capabilities->selected_encoder_name, name.data(), bytes);
        capabilities->selected_encoder_name[bytes] = '\0';
        activation->ShutdownObject();
        return BP_MEDIA_OK;
    }
    write_error(error_message, error_message_capacity,
                format_hresult("IMFActivate::ActivateObject", last_error));
    return BP_MEDIA_ENCODER_ACTIVATION_FAILED;
}

extern "C" int32_t bp_media_encoder_create(
    const bp_media_encoder_config* config, void* d3d11_device,
    bp_media_encoder** encoder, char* error_message, size_t error_capacity) {
    if (config == nullptr || d3d11_device == nullptr || encoder == nullptr ||
        config->width == 0 || config->height == 0 || config->frames_per_second == 0 ||
        config->bitrate_bits_per_second == 0 || config->keyframe_interval_frames == 0) {
        write_error(error_message, error_capacity, "invalid encoder arguments");
        return BP_MEDIA_INVALID_ARGUMENT;
    }
    *encoder = nullptr;
    // This function runs on the WGC callback thread. Prioritize capture and
    // encoding above ordinary desktop work without changing the user's power
    // plan or using a process-wide priority class.
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
    std::unique_ptr<bp_media_encoder> value(new (std::nothrow) bp_media_encoder{});
    if (!value) return BP_MEDIA_ENCODER_CONFIGURATION_FAILED;
    if (FAILED(value->com.result())) return BP_MEDIA_COM_INITIALIZATION_FAILED;
    if (FAILED(value->media_foundation.result())) return BP_MEDIA_FOUNDATION_STARTUP_FAILED;
    std::string error;
    const HRESULT result = value->implementation.initialize(
        *config, static_cast<ID3D11Device*>(d3d11_device), error);
    if (FAILED(result)) {
        write_error(error_message, error_capacity, error);
        return status_from_hresult(result, BP_MEDIA_ENCODER_CONFIGURATION_FAILED);
    }
    *encoder = value.release();
    return BP_MEDIA_OK;
}

extern "C" int32_t bp_media_encoder_encode_texture(
    bp_media_encoder* encoder, void* texture, int64_t timestamp_100ns,
    int64_t duration_100ns, bp_media_encoded_frame* frame,
    char* error_message, size_t error_capacity) {
    if (encoder == nullptr || texture == nullptr || frame == nullptr ||
        timestamp_100ns < 0 || duration_100ns <= 0) return BP_MEDIA_INVALID_ARGUMENT;
    *frame = {};
    std::vector<uint8_t> bytes;
    int64_t output_timestamp = 0;
    bool keyframe = false;
    std::string error;
    const HRESULT result = encoder->implementation.encode(
        static_cast<ID3D11Texture2D*>(texture), timestamp_100ns, duration_100ns,
        bytes, output_timestamp, keyframe, error);
    if (result == S_FALSE) return BP_MEDIA_NO_OUTPUT;
    if (FAILED(result)) {
        write_error(error_message, error_capacity, error);
        return status_from_hresult(result, BP_MEDIA_ENCODE_FAILED);
    }
    auto* data = static_cast<uint8_t*>(CoTaskMemAlloc(bytes.size()));
    if (data == nullptr) return BP_MEDIA_ENCODE_FAILED;
    std::memcpy(data, bytes.data(), bytes.size());
    frame->data = data;
    frame->data_length = bytes.size();
    frame->timestamp_100ns = output_timestamp;
    frame->keyframe = static_cast<uint8_t>(keyframe);
    return BP_MEDIA_OK;
}

extern "C" int32_t bp_media_encoder_set_bitrate(
    bp_media_encoder* encoder, uint32_t bitrate, char* error_message, size_t error_capacity) {
    if (encoder == nullptr || bitrate == 0) return BP_MEDIA_INVALID_ARGUMENT;
    std::string error;
    const HRESULT result = encoder->implementation.set_bitrate(bitrate, error);
    if (FAILED(result)) {
        write_error(error_message, error_capacity, error);
        return BP_MEDIA_ENCODER_CONFIGURATION_FAILED;
    }
    return BP_MEDIA_OK;
}

extern "C" int32_t bp_media_encoder_request_keyframe(
    bp_media_encoder* encoder, char* error_message, size_t error_capacity) {
    if (encoder == nullptr) return BP_MEDIA_INVALID_ARGUMENT;
    std::string error;
    const HRESULT result = encoder->implementation.request_keyframe(error);
    if (FAILED(result)) {
        write_error(error_message, error_capacity, error);
        return BP_MEDIA_ENCODER_CONFIGURATION_FAILED;
    }
    return BP_MEDIA_OK;
}

extern "C" void bp_media_encoded_frame_release(bp_media_encoded_frame* frame) {
    if (frame != nullptr && frame->data != nullptr) {
        CoTaskMemFree(frame->data);
        *frame = {};
    }
}

extern "C" void bp_media_encoder_destroy(bp_media_encoder* encoder) { delete encoder; }

#else
extern "C" int32_t bp_media_probe_h264_hardware(
    bp_media_h264_capabilities*, char*, size_t) {
    return BP_MEDIA_HARDWARE_ENCODER_UNAVAILABLE;
}
#endif
