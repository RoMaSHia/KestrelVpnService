use std::os::unix::io::{FromRawFd, IntoRawFd, OwnedFd, RawFd};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::sync::{Arc, Mutex, Once};

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};

use crate::config::CoreConfig;
use crate::proxy::socket::SocketProtector;
use crate::tun_engine::RunningEngine;
use crate::{build_core_info_json, format_ping_reply};

static LOGGER_INIT: Once = Once::new();
static ENGINE_SLOT: Mutex<Option<RunningEngine>> = Mutex::new(None);

/// Initializes platform logging once across the lifetime of the native library.
pub fn init_logger() {
    LOGGER_INIT.call_once(|| {
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("KestrelCore"),
            );
        }
        log::info!("Kestrel native core logger initialized");
    });
}

struct JniSocketProtector {
    jvm: JavaVM,
    callback_obj: GlobalRef,
}

impl SocketProtector for JniSocketProtector {
    fn protect(&self, fd: RawFd) -> bool {
        let Ok(mut env) = self.jvm.attach_current_thread() else {
            log::error!("Failed to attach JVM thread for protect(fd={fd})");
            return false;
        };

        match env.call_method(
            self.callback_obj.as_obj(),
            "protect",
            "(I)Z",
            &[JValue::Int(fd as jint)],
        ) {
            Ok(val) => match val.z() {
                Ok(allowed) => allowed,
                Err(err) => {
                    log::error!("Unexpected return value from protect(fd={fd}): {err:?}");
                    false
                }
            },
            Err(err) => {
                log::error!("JNI call to protect(fd={fd}) failed: {err:?}");
                let _ = env.exception_clear();
                false
            }
        }
    }
}

fn to_jstring(env: &JNIEnv<'_>, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(err) => {
            log::error!("Failed to allocate Java string in JNI: {err:?}");
            ptr::null_mut()
        }
    }
}

fn read_jstring(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s)
        .ok()
        .map(|js| js.to_string_lossy().into_owned())
}

/// JNI entry point returning serialized [`crate::CoreInfo`] JSON.
#[no_mangle]
pub extern "system" fn Java_dev_kestrel_proxy_core_NativeBridge_nativeGetCoreInfo<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
) -> jstring {
    init_logger();
    let result = catch_unwind(AssertUnwindSafe(|| {
        let json = build_core_info_json();
        to_jstring(&env, &json)
    }));

    result.unwrap_or_else(|_| {
        log::error!("Panic caught inside nativeGetCoreInfo");
        ptr::null_mut()
    })
}

/// JNI entry point echoing a formatted ping reply back to Kotlin.
#[no_mangle]
pub extern "system" fn Java_dev_kestrel_proxy_core_NativeBridge_nativePing<'local>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    message: JString<'local>,
) -> jstring {
    init_logger();
    let result = catch_unwind(AssertUnwindSafe(|| {
        let input = read_jstring(&mut env, &message).unwrap_or_default();
        let reply = format_ping_reply(&input);
        to_jstring(&env, &reply)
    }));

    result.unwrap_or_else(|_| {
        log::error!("Panic caught inside nativePing");
        ptr::null_mut()
    })
}

/// Starts the Rust `tun2socks` engine over `tun_fd`.
/// Returns `0` on success, negative error code on failure.
#[no_mangle]
pub extern "system" fn Java_dev_kestrel_proxy_core_NativeBridge_nativeStart<'local>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    tun_fd: jint,
    config_json: JString<'local>,
    protector: JObject<'local>,
) -> jint {
    init_logger();
    let result = catch_unwind(AssertUnwindSafe(|| -> jint {
        if tun_fd < 0 || protector.is_null() {
            return -1;
        }
        // Take ownership immediately so early validation failures never leak tun_fd.
        let owned_tun = unsafe { OwnedFd::from_raw_fd(tun_fd as RawFd) };
        let Some(cfg_str) = read_jstring(&mut env, &config_json) else {
            return -2;
        };
        let config: CoreConfig = match serde_json::from_str(&cfg_str) {
            Ok(c) => c,
            Err(err) => {
                log::error!("Invalid CoreConfig JSON: {err}");
                return -3;
            }
        };

        let Ok(jvm) = env.get_java_vm() else {
            return -4;
        };
        let Ok(callback_obj) = env.new_global_ref(protector) else {
            return -5;
        };

        let protector_impl: Arc<dyn SocketProtector> = Arc::new(JniSocketProtector {
            jvm,
            callback_obj,
        });

        let mut slot = match ENGINE_SLOT.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        if let Some(prev) = slot.take() {
            prev.stop();
        }

        let raw_fd = owned_tun.into_raw_fd();
        match RunningEngine::start(raw_fd, config, protector_impl) {
            Ok(engine) => {
                *slot = Some(engine);
                log::info!("Kestrel Rust engine started on tun_fd={tun_fd}");
                0
            }
            Err(err) => {
                log::error!("Failed to start Rust engine: {err}");
                -6
            }
        }
    }));

    result.unwrap_or(-99)
}

/// Stops the running `tun2socks` engine and releases the TUN descriptor.
#[no_mangle]
pub extern "system" fn Java_dev_kestrel_proxy_core_NativeBridge_nativeStop<'local>(
    _env: JNIEnv<'local>,
    _thiz: JObject<'local>,
) -> jint {
    init_logger();
    let result = catch_unwind(AssertUnwindSafe(|| -> jint {
        let engine = match ENGINE_SLOT.lock() {
            Ok(mut g) => g.take(),
            Err(mut poisoned) => poisoned.get_mut().take(),
        };
        if let Some(eng) = engine {
            eng.stop();
            log::info!("Kestrel Rust engine stopped");
        }
        0
    }));
    result.unwrap_or(-99)
}

/// Hot-reloads routing rules, DNS settings, and proxy config while the engine is running.
#[no_mangle]
pub extern "system" fn Java_dev_kestrel_proxy_core_NativeBridge_nativeUpdateConfig<'local>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    config_json: JString<'local>,
) -> jint {
    init_logger();
    let result = catch_unwind(AssertUnwindSafe(|| -> jint {
        let Some(cfg_str) = read_jstring(&mut env, &config_json) else {
            return -2;
        };
        let config: CoreConfig = match serde_json::from_str(&cfg_str) {
            Ok(c) => c,
            Err(err) => {
                log::error!("Invalid update CoreConfig JSON: {err}");
                return -3;
            }
        };

        let guard = match ENGINE_SLOT.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        if let Some(eng) = guard.as_ref() {
            eng.update_config(config);
            0
        } else {
            -1
        }
    }));
    result.unwrap_or(-99)
}

/// Returns current traffic counters and speeds as JSON.
#[no_mangle]
pub extern "system" fn Java_dev_kestrel_proxy_core_NativeBridge_nativeGetStats<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let json = match ENGINE_SLOT.lock() {
            Ok(g) => g
                .as_ref()
                .map(|e| e.stats_json())
                .unwrap_or_else(|| "{}".to_string()),
            Err(_) => "{}".to_string(),
        };
        to_jstring(&env, &json)
    }));
    result.unwrap_or_else(|_| ptr::null_mut())
}

/// Returns recent connection log entries with `id > since_id` as a JSON array.
#[no_mangle]
pub extern "system" fn Java_dev_kestrel_proxy_core_NativeBridge_nativePollLogs<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    since_id: jlong,
    limit: jint,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let since = since_id.max(0) as u64;
        let cap = limit.max(1) as usize;
        let json = match ENGINE_SLOT.lock() {
            Ok(g) => g
                .as_ref()
                .map(|e| e.poll_logs_json(since, cap))
                .unwrap_or_else(|| "[]".to_string()),
            Err(_) => "[]".to_string(),
        };
        to_jstring(&env, &json)
    }));
    result.unwrap_or_else(|_| ptr::null_mut())
}
