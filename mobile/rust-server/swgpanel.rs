// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// swg-panel integration knobs for csqtt. Every field defaults to the upstream/stock value, so a binary
// invoked WITHOUT any of the new flags behaves like stock csqtt. Set once at boot from the CLI args (each
// instance is its own process → a process-global OnceLock is the whole config).

use std::path::PathBuf;
use std::sync::OnceLock;

#[derive(Clone, Debug)]
pub struct InstanceCfg {
    pub iface: String,          // TUN interface name (stock: csqtt1)
    pub gw_addr: String,        // gateway/.1 address (stock: 10.66.67.1)
    pub subnet_prefix: [u8; 3], // first 3 octets of the /24 (stock: 10.66.67)
    pub subnet_cidr: String,    // stock: 10.66.67.0/24
    // no_nat: the node owns forwarding, NAT and the firewall. Inert from 2.1.5 to 2.1.9 (the binary touched none of
    // it); 2.5 sets host-wide sysctls and installs FORWARD/MASQUERADE rules at start (tokio_io KernelNetworking),
    // which this flag now skips. Every swg-noded unit passes it.
    pub no_nat: bool,
    pub headless: bool,         // skip the web admin panel (binds 0.0.0.0), its TLS cert generation and
                                // reload loop, and the DPI/syscalls monitor sockets — the swg-panel owns
                                // everything via --desired, and the fixed loopback 46003/46004 collide on
                                // a multi-instance node. Stock false ⇒ unset behaves exactly like upstream.
    pub desired_path: Option<PathBuf>, // declarative desired-passwords file (panel-owned)
    // instance: the name of this server on the host (stock: csqtt). A second standalone server — the app's "next to
    // 2.1.9" deploy — runs as e.g. csqtt25 and must never touch the first: it names the systemd unit the CLI and the
    // web panel's restart act on, the netfilter comments (stock deploy.sh deletes every rule whose comment CONTAINS
    // CSQTT_MANAGED / CSQTT_TPROXY, so CSQTT25_MANAGED is out of its reach), the local-proxy policy table and fwmark
    // (stock wipes table 30001 / mark 0x7531 at every start), the TPROXY status sockets and the web session cookie.
    // One non-default slot: the deploy installs at most one side instance per host.
    pub instance: String,
    pub managed_tag: String,    // KernelNetworking rules (stock: CSQTT_MANAGED)
    pub tproxy_tag: String,     // local-proxy TPROXY rules, ":<port>" appended (stock: CSQTT_TPROXY)
}

pub const DEFAULT_INSTANCE: &str = "csqtt";
// Every netfilter comment stock csqtt (and its deploy.sh) deletes by substring.
const STOCK_TAGS: [&str; 6] =
    ["CSQTT_MANAGED", "CSQTT_MIRRORED", "CSQTT_TPROXY", "CSQTT_LOCAL_SOCKS", "CSQTT_SOCKS", "CSQTT_CASCADE_NO_QUIC"];

impl Default for InstanceCfg {
    fn default() -> Self {
        Self {
            iface: "csqtt1".to_owned(),
            gw_addr: "10.66.67.1".to_owned(),
            subnet_prefix: [10, 66, 67],
            subnet_cidr: "10.66.67.0/24".to_owned(),
            no_nat: false,
            headless: false,
            desired_path: None,
            instance: DEFAULT_INSTANCE.to_owned(),
            managed_tag: "CSQTT_MANAGED".to_owned(),
            tproxy_tag: "CSQTT_TPROXY".to_owned(),
        }
    }
}

static CFG: OnceLock<InstanceCfg> = OnceLock::new();

pub fn init(cfg: InstanceCfg) {
    let _ = CFG.set(cfg);
}
pub fn get() -> &'static InstanceCfg {
    CFG.get_or_init(InstanceCfg::default)
}
pub fn iface() -> &'static str {
    &get().iface
}
pub fn gw_addr() -> &'static str {
    &get().gw_addr
}
pub fn subnet_cidr() -> &'static str {
    &get().subnet_cidr
}
pub fn subnet_prefix() -> [u8; 3] {
    get().subnet_prefix
}
// The address `last` of this instance's /24 (stock: 10.66.67.<last>).
pub fn subnet_addr(last: u8) -> String {
    let p = subnet_prefix();
    format!("{}.{}.{}.{last}", p[0], p[1], p[2])
}
pub fn no_nat() -> bool {
    get().no_nat
}
pub fn headless() -> bool {
    get().headless
}
pub fn desired_path() -> Option<&'static PathBuf> {
    get().desired_path.as_ref()
}

pub fn instance() -> &'static str {
    &get().instance
}
pub fn is_default_instance() -> bool {
    get().instance == DEFAULT_INSTANCE
}
pub fn managed_tag() -> &'static str {
    &get().managed_tag
}
pub fn tproxy_tag() -> &'static str {
    &get().tproxy_tag
}
// Local-proxy policy routing: stock owns table/priority 30001 and fwmark 0x7531 and flushes them at every start; the
// side instance takes 30002 / 0x7532. The marks share bits (0x7530), but neither value matches the other's mask
// (0x7531 & 0x7532 = 0x7530 ≠ 0x7532, and the reverse), so each rule sees only its own. Its TPROXY ports start at 10766;
// they can still meet stock's 10666+id range, which the listener's EADDRINUSE retry already handles.
pub fn tproxy_table() -> &'static str {
    if is_default_instance() { "30001" } else { "30002" }
}
pub fn tproxy_mark() -> &'static str {
    if is_default_instance() { "0x7531/0x7531" } else { "0x7532/0x7532" }
}
pub fn tproxy_base_port() -> u16 {
    if is_default_instance() { 10666 } else { 10766 }
}
// Two panels on one host share its cookies (cookies are not port-scoped): a login to one logged the other out.
pub fn session_cookie() -> String {
    if is_default_instance() { "csqtt_session".to_owned() } else { format!("{}_session", get().instance) }
}

// Under --desired the node runs no IPv6 for csqtt (the TUN carries no v6 address and nothing routes it), so an IPv6
// uplink packet is dropped like a spoofed IPv4 source — stock passes any non-IPv4 packet unchecked.
pub fn ipv4_only_uplink() -> bool {
    desired_path().is_some()
}

// Build a config from the optional flags. Only /24 is accepted: the datapath route table indexes a session
// by the packet's last octet, so a differently-sized mask would need a different table shape.
pub fn build(
    iface: Option<String>,
    tun_addr: Option<String>,
    no_nat: bool,
    headless: bool,
    desired: Option<PathBuf>,
    instance: Option<String>,
) -> anyhow::Result<InstanceCfg> {
    let mut cfg = InstanceCfg::default();
    if let Some(name) = instance
        && !name.is_empty()
        && name != DEFAULT_INSTANCE
    {
        // a unit name, a path component and a netfilter comment: lower-case letters, digits and '-' only, so the
        // tag never gains the '_' that would make it a superstring of a stock one ("CSQTT_…")
        let ok = name.len() <= 15
            && name.starts_with(|c: char| c.is_ascii_lowercase())
            && name.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
        if !ok {
            anyhow::bail!("--instance must be 1-15 of a-z, 0-9 and '-', starting with a letter (got {name})");
        }
        let up = name.to_ascii_uppercase();
        cfg.managed_tag = format!("{up}_MANAGED");
        cfg.tproxy_tag = format!("{up}_TPROXY");
        // …and a name ending in "csqtt" (old-csqtt → OLD-CSQTT_MANAGED) still contains a stock tag
        for tag in [&cfg.managed_tag, &cfg.tproxy_tag] {
            if let Some(stock) = STOCK_TAGS.iter().find(|stock| tag.contains(*stock)) {
                anyhow::bail!("--instance {name}: its rule tag {tag} contains the stock {stock}, which stock csqtt deletes");
            }
        }
        cfg.instance = name;
    }
    if let Some(i) = iface
        && !i.is_empty()
    {
        cfg.iface = i;
    }
    if let Some(a) = tun_addr {
        let a = a.trim();
        if !a.is_empty() {
            let (ip_part, mask) = a.split_once('/').unwrap_or((a, "24"));
            if mask != "24" {
                anyhow::bail!("--tun-addr must be a /24 (got /{mask})");
            }
            let octs: Vec<u8> = ip_part
                .split('.')
                .map(|o| o.parse::<u8>())
                .collect::<Result<_, _>>()
                .map_err(|_| anyhow::anyhow!("bad --tun-addr {a}"))?;
            if octs.len() != 4 {
                anyhow::bail!("bad --tun-addr {a}");
            }
            // .2 is the owner's address and .3–.250 the client pool (ip_in_pool, get_next_ip): a server address in
            // there is handed to a client too, whose downlink then stays on the server. Outside that range anything
            // a node already uses keeps working.
            if (2..=250).contains(&octs[3]) {
                anyhow::bail!("--tun-addr {a}: the server's address must not be in the client range .2–.250 (use .1)");
            }
            cfg.subnet_prefix = [octs[0], octs[1], octs[2]];
            cfg.gw_addr = format!("{}.{}.{}.{}", octs[0], octs[1], octs[2], octs[3]);
            cfg.subnet_cidr = format!("{}.{}.{}.0/24", octs[0], octs[1], octs[2]);
        }
    }
    cfg.no_nat = no_nat;
    cfg.headless = headless;
    cfg.desired_path = desired;
    Ok(cfg)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tun_addr_in_the_client_range_is_refused() {
        for bad in ["10.66.68.2/24", "10.66.68.5/24", "10.66.68.250/24"] {
            assert!(build(None, Some(bad.to_owned()), false, false, None, None).is_err(), "{bad}");
        }
        for ok in ["10.66.68.1/24", "10.66.68.0/24", "10.66.68.254/24"] {
            assert!(build(None, Some(ok.to_owned()), false, false, None, None).is_ok(), "{ok}");
        }
    }

    #[test]
    fn no_flags_is_stock() {
        let cfg = build(None, None, false, false, None, None).unwrap();
        assert_eq!(cfg.iface, "csqtt1");
        assert_eq!(cfg.gw_addr, "10.66.67.1");
        assert_eq!(cfg.subnet_prefix, [10, 66, 67]);
        assert_eq!(cfg.subnet_cidr, "10.66.67.0/24");
        assert!(!cfg.no_nat && !cfg.headless && cfg.desired_path.is_none());
        assert_eq!(cfg.instance, "csqtt");
        assert_eq!((cfg.managed_tag.as_str(), cfg.tproxy_tag.as_str()), ("CSQTT_MANAGED", "CSQTT_TPROXY"));
        // naming the default instance is the same as not naming one
        let named = build(None, None, false, false, None, Some("csqtt".into())).unwrap();
        assert_eq!((named.instance.as_str(), named.managed_tag.as_str()), ("csqtt", "CSQTT_MANAGED"));
    }

    #[test]
    fn a_side_instance_is_out_of_stock_cleanup_reach() {
        let cfg = build(Some("csqtt25".into()), Some("10.66.68.1/24".into()), false, false, None, Some("csqtt25".into())).unwrap();
        assert_eq!(cfg.instance, "csqtt25");
        assert_eq!((cfg.managed_tag.as_str(), cfg.tproxy_tag.as_str()), ("CSQTT25_MANAGED", "CSQTT25_TPROXY"));
        // stock deploy.sh deletes every rule whose comment CONTAINS one of these; ours must contain none of them
        for stock in ["CSQTT_MANAGED", "CSQTT_MIRRORED", "CSQTT_TPROXY", "CSQTT_LOCAL_SOCKS", "CSQTT_SOCKS", "CSQTT_CASCADE_NO_QUIC"] {
            assert!(!cfg.managed_tag.contains(stock) && !cfg.tproxy_tag.contains(stock), "{stock}");
        }
        for bad in ["Csqtt25", "csqtt_25", "25csqtt", "csqtt/25", "a-very-long-instance-name", "old-csqtt", "xcsqtt"] {
            assert!(build(None, None, false, false, None, Some(bad.into())).is_err(), "{bad}");
        }
    }

    #[test]
    fn tun_addr_sets_the_whole_subnet() {
        let cfg = build(Some("csqtt7".into()), Some("10.66.71.1/24".into()), true, true, None, None).unwrap();
        assert_eq!(cfg.iface, "csqtt7");
        assert_eq!(cfg.gw_addr, "10.66.71.1");
        assert_eq!(cfg.subnet_prefix, [10, 66, 71]);
        assert_eq!(cfg.subnet_cidr, "10.66.71.0/24");
        assert!(cfg.no_nat && cfg.headless);
        // a bare address means /24
        assert_eq!(build(None, Some("10.9.8.1".into()), false, false, None, None).unwrap().subnet_prefix, [10, 9, 8]);
    }

    #[test]
    fn only_a_24_is_accepted() {
        assert!(build(None, Some("10.66.71.1/16".into()), false, false, None, None).is_err());
        assert!(build(None, Some("10.66.71/24".into()), false, false, None, None).is_err());
        assert!(build(None, Some("10.66.300.1/24".into()), false, false, None, None).is_err());
    }
}
