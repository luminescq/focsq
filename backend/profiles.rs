// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

use primp::{Impersonate, ImpersonateOS};
use rand::RngExt;
use serde::Deserialize;

#[derive(Clone, Debug)]
pub struct Profile {
    pub impersonate: Impersonate,
    pub os: ImpersonateOS,
    pub user_agent: String,
    pub sec_ch_ua: String,
    pub sec_ch_ua_mobile: String,
    pub sec_ch_ua_platform: String,
}

impl Default for Profile {
    fn default() -> Self {
        Self {
            impersonate: Impersonate::Chrome,
            os: ImpersonateOS::Windows,
            user_agent: String::new(),
            sec_ch_ua: String::new(),
            sec_ch_ua_mobile: String::new(),
            sec_ch_ua_platform: String::new(),
        }
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct SavedProfile {
    #[serde(default)]
    #[allow(dead_code)]
    pub user_agent: String,
    pub device_json: String,
    pub browser_fp: String,
}

pub fn load_saved_profile() -> Option<SavedProfile> {
    let data = std::fs::read("vk_profile.json").ok()?;
    serde_json::from_slice(&data).ok()
}

struct WeightedProfile {
    impersonate: Impersonate,
    os: ImpersonateOS,
    weight: u32,
}

fn wp(impersonate: Impersonate, os: ImpersonateOS, weight: u32) -> WeightedProfile {
    WeightedProfile {
        impersonate,
        os,
        weight,
    }
}

fn pick_weighted(pool: &[WeightedProfile]) -> (Impersonate, ImpersonateOS) {
    let total: u32 = pool.iter().map(|entry| entry.weight).sum();
    let mut roll = rand::rng().random_range(0..total);
    for entry in pool {
        if roll < entry.weight {
            return (entry.impersonate, entry.os);
        }
        roll -= entry.weight;
    }
    let last = &pool[pool.len() - 1];
    (last.impersonate, last.os)
}

fn weighted_browser_pool(
    versions: &[(Impersonate, u32)],
    operating_systems: &[(ImpersonateOS, u32)],
) -> Vec<WeightedProfile> {
    versions
        .iter()
        .flat_map(|(impersonate, version_weight)| {
            operating_systems
                .iter()
                .map(move |(os, os_weight)| wp(*impersonate, *os, version_weight * os_weight))
        })
        .collect()
}

fn chrome_pool() -> Vec<WeightedProfile> {
    weighted_browser_pool(
        &[
            (Impersonate::ChromeV148, 35),
            (Impersonate::ChromeV147, 25),
            (Impersonate::ChromeV146, 18),
            (Impersonate::ChromeV145, 12),
            (Impersonate::ChromeV144, 10),
        ],
        &[
            (ImpersonateOS::Android, 55),
            (ImpersonateOS::Windows, 20),
            (ImpersonateOS::MacOS, 10),
            (ImpersonateOS::Linux, 10),
            (ImpersonateOS::IOS, 5),
        ],
    )
}

fn firefox_pool() -> Vec<WeightedProfile> {
    weighted_browser_pool(
        &[
            (Impersonate::FirefoxV148, 40),
            (Impersonate::FirefoxV147, 28),
            (Impersonate::FirefoxV146, 20),
            (Impersonate::FirefoxV140, 12),
        ],
        &[
            (ImpersonateOS::Android, 55),
            (ImpersonateOS::Windows, 20),
            (ImpersonateOS::MacOS, 10),
            (ImpersonateOS::Linux, 10),
            (ImpersonateOS::IOS, 5),
        ],
    )
}

fn safari_pool() -> Vec<WeightedProfile> {
    weighted_browser_pool(
        &[
            (Impersonate::SafariV26_3, 40),
            (Impersonate::SafariV26, 35),
            (Impersonate::SafariV18_5, 25),
        ],
        &[(ImpersonateOS::IOS, 70), (ImpersonateOS::MacOS, 30)],
    )
}

fn edge_pool() -> Vec<WeightedProfile> {
    weighted_browser_pool(
        &[
            (Impersonate::EdgeV148, 35),
            (Impersonate::EdgeV147, 25),
            (Impersonate::EdgeV146, 18),
            (Impersonate::EdgeV145, 12),
            (Impersonate::EdgeV144, 10),
        ],
        &[
            (ImpersonateOS::Android, 60),
            (ImpersonateOS::Windows, 20),
            (ImpersonateOS::MacOS, 8),
            (ImpersonateOS::Linux, 7),
            (ImpersonateOS::IOS, 5),
        ],
    )
}

fn opera_pool() -> Vec<WeightedProfile> {
    weighted_browser_pool(
        &[
            (Impersonate::OperaV131, 30),
            (Impersonate::OperaV130, 24),
            (Impersonate::OperaV129, 18),
            (Impersonate::OperaV128, 13),
            (Impersonate::OperaV127, 9),
            (Impersonate::OperaV126, 6),
        ],
        &[
            (ImpersonateOS::Android, 60),
            (ImpersonateOS::Windows, 20),
            (ImpersonateOS::MacOS, 8),
            (ImpersonateOS::Linux, 7),
            (ImpersonateOS::IOS, 5),
        ],
    )
}

/// OS-платформа для HTTP-фингерпринта.
///
/// Раньше random_profile выбирал OS независимо от реальной платформы
/// (например, Linux-клиент мог отправить Android или Windows-профиль). Для
/// VK Calls это создаёт противоречивую идентичность: desktop-программа с
/// мобильным UA и TLS-профилем. Случайной оставляем только версию браузера.
fn native_platform_os() -> ImpersonateOS {
    #[cfg(target_os = "windows")]
    {
        return ImpersonateOS::Windows;
    }
    #[cfg(target_os = "linux")]
    {
        return ImpersonateOS::Linux;
    }
    #[cfg(target_os = "macos")]
    {
        return ImpersonateOS::MacOS;
    }
    #[cfg(target_os = "android")]
    {
        return ImpersonateOS::Android;
    }
    #[cfg(target_os = "ios")]
    {
        return ImpersonateOS::IOS;
    }
    #[allow(unreachable_code)]
    ImpersonateOS::Windows
}

pub fn random_profile(fingerprint: &str) -> Profile {
    let (impersonate, selected_os) = match fingerprint {
        "safari" => pick_weighted(&safari_pool()),
        "firefox" => pick_weighted(&firefox_pool()),
        "edge" => pick_weighted(&edge_pool()),
        "opera" => pick_weighted(&opera_pool()),
        _ => pick_weighted(&chrome_pool()),
    };

    // Safari поддерживает только macOS/iOS в primp. На Windows/Linux
    // сохраняем валидный Safari/macOS профиль; остальные браузеры обязаны
    // совпадать с реальной desktop-платформой.
    let os = if fingerprint == "safari" {
        match selected_os {
            ImpersonateOS::IOS | ImpersonateOS::MacOS => selected_os,
            _ => ImpersonateOS::MacOS,
        }
    } else {
        native_platform_os()
    };

    Profile {
        impersonate,
        os,
        user_agent: String::new(),
        sec_ch_ua: String::new(),
        sec_ch_ua_mobile: String::new(),
        sec_ch_ua_platform: String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn saved_profile_accepts_go_partial_json() {
        let saved: SavedProfile =
            serde_json::from_str(r#"{"device_json":"{}","browser_fp":"0123456789abcdef"}"#)
                .unwrap();
        assert_eq!(saved.device_json, "{}");
        assert_eq!(saved.browser_fp, "0123456789abcdef");
        assert!(saved.user_agent.is_empty());
    }

    #[test]
    fn desktop_profiles_use_native_os_and_vary_browser_versions() {
        let mut chrome_versions = HashSet::new();
        let mut firefox_versions = HashSet::new();
        for _ in 0..1_000 {
            let chrome = random_profile("chrome");
            let firefox = random_profile("firefox");
            assert_eq!(chrome.os, native_platform_os());
            assert_eq!(firefox.os, native_platform_os());
            chrome_versions.insert(format!("{:?}", chrome.impersonate));
            firefox_versions.insert(format!("{:?}", firefox.impersonate));
        }
        assert!(chrome_versions.len() >= 4);
        assert!(firefox_versions.len() >= 4);
    }

    #[test]
    fn edge_and_opera_profiles_use_native_os() {
        for fingerprint in ["edge", "opera"] {
            for _ in 0..100 {
                assert_eq!(random_profile(fingerprint).os, native_platform_os());
            }
        }
    }

    #[test]
    fn safari_profiles_are_limited_to_apple_platforms() {
        assert_eq!(safari_pool().len(), 6);
        for _ in 0..1_000 {
            let profile = random_profile("safari");
            assert!(matches!(
                profile.os,
                ImpersonateOS::MacOS | ImpersonateOS::IOS
            ));
        }
    }

    #[test]
    fn default_fingerprint_is_chrome() {
        let profile = random_profile("anything");
        let name = format!("{:?}", profile.impersonate);
        assert!(
            name.contains("Chrome"),
            "Default fingerprint should be Chrome, got {name}"
        );
    }
}
