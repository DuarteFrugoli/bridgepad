fn main() {
    println!("cargo:rerun-if-changed=../../native/windows-media/include/bridgepad_windows_media.h");
    println!("cargo:rerun-if-changed=../../native/windows-media/src/bridgepad_windows_media.cpp");

    if std::env::var_os("CARGO_CFG_WINDOWS").is_none() {
        return;
    }

    cc::Build::new()
        .cpp(true)
        .file("../../native/windows-media/src/bridgepad_windows_media.cpp")
        .include("../../native/windows-media/include")
        .flag_if_supported("/std:c++17")
        .flag_if_supported("/EHsc")
        .warnings(true)
        .compile("bridgepad_windows_media_native");

    for library in ["ole32", "oleaut32", "mf", "mfplat", "mfuuid"] {
        println!("cargo:rustc-link-lib={library}");
    }
}
