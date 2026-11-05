// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

use crate::{
    packet::{PACKET_CAPACITY, PacketBuf},
    wrap::WRAP_KEY_LEN,
};
use aes::cipher::{InnerIvInit, KeyInit, StreamCipher};
use anyhow::{Result, bail};
use hmac::{Hmac, Mac};
use rand::RngExt;
use rand::{Rng, SeedableRng, rngs::StdRng};
use sha1::Sha1;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

type Aes128Ctr128BE = ctr::Ctr128BE<aes::Aes128>;
type Aes128CtrCore = ctr::CtrCore<aes::Aes128, ctr::flavors::Ctr128BE>;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ObfsMode {
    Audio,
    Video,
}

impl ObfsMode {
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "audio" => Ok(Self::Audio),
            "video" => Ok(Self::Video),
            _ => bail!("неподдерживаемый режим обфускации: {value}"),
        }
    }
}

pub struct ObfsConfig {
    pub padding_max: usize,
    pub ssrc: u32,
    pub payload_type: u8,
    pub mode: ObfsMode,
}

impl ObfsConfig {
    pub fn new(mode: ObfsMode) -> Self {
        let (payload_type, padding_max) = match mode {
            ObfsMode::Audio => (111, 24),
            ObfsMode::Video => (96, 60),
        };
        Self {
            padding_max,
            ssrc: seeded_rng().next_u32(),
            payload_type,
            mode,
        }
    }
}

pub struct ObfsState {
    count: u64,
    initial_timestamp: u32,
    initial_abs_send_time: u32,
    initial_sequence: u16,
    transport_sequence: u16,
    started: Instant,
    rng: StdRng,
}

impl ObfsState {
    pub fn new() -> Self {
        let mut rng = seeded_rng();
        let wall_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        Self {
            count: 0,
            initial_timestamp: rng.next_u32(),
            initial_abs_send_time: (((wall_ms * 262_144) / 1_000) as u32) & 0x00ff_ffff,
            initial_sequence: rng.next_u32() as u16,
            transport_sequence: rng.next_u32() as u16,
            started: Instant::now(),
            rng,
        }
    }

    fn next(&mut self) -> (u64, u16) {
        let count = self.count;
        let transport = self.transport_sequence;
        self.count = self.count.wrapping_add(1);
        self.transport_sequence = self.transport_sequence.wrapping_add(1);
        (count, transport)
    }
}

pub struct ObfsCipher {
    aes: aes::Aes128,
    hmac: Hmac<Sha1>,
}

impl ObfsCipher {
    pub fn new(key: [u8; WRAP_KEY_LEN]) -> Result<Self> {
        Ok(Self {
            aes: aes::Aes128::new_from_slice(&key[..16])
                .map_err(|_| anyhow::anyhow!("AES key rejected"))?,
            hmac: <Hmac<Sha1> as hmac::digest::KeyInit>::new_from_slice(&key[16..])
                .map_err(|_| anyhow::anyhow!("HMAC key rejected"))?,
        })
    }

    pub fn wrap(
        &self,
        packet: &mut PacketBuf,
        config: &ObfsConfig,
        state: &mut ObfsState,
    ) -> Result<()> {
        if packet.is_empty() {
            bail!("obfs: empty payload");
        }
        let payload_len = packet.len();
        let (count, transport_sequence) = state.next();
        let sequence = config.initial_sequence(state, count);
        let elapsed = state.started.elapsed();
        let payload_type = config.payload_type;
        let timestamp = state
            .initial_timestamp
            .wrapping_add(duration_ticks(elapsed, rtp_clock_rate(payload_type)));
        let padding_max = config.padding_max;
        let padding_random = if padding_max == 0 {
            0
        } else {
            state.rng.random_range(0..padding_max)
        };
        let padding_total = padding_random + 1;
        // CSQTT 2.5 uses AES-CTR + HMAC-SHA1-80 for both media profiles.
        // The RTP padding is encrypted together with the IP/control payload.
        let tail = padding_total + 10;
        let range = packet.range();
        if range.start < 24
            || range
                .end
                .checked_add(tail)
                .is_none_or(|end| end > PACKET_CAPACITY)
        {
            bail!("obfs: packet exceeds buffer capacity");
        }
        let absolute_send_time = state
            .initial_abs_send_time
            .wrapping_add(duration_ticks(elapsed, 262_144))
            & 0x00ff_ffff;
        let header = packet.prepend(24)?;
        header.fill(0);
        header[0] = 0xb0 | 0x20;
        header[1] = payload_type & 0x7f;
        if payload_type == 96 && state.rng.random_range(0..5) == 0 {
            header[1] |= 0x80;
        }
        header[2..4].copy_from_slice(&sequence.to_be_bytes());
        header[4..8].copy_from_slice(&timestamp.to_be_bytes());
        header[8..12].copy_from_slice(&config.ssrc.to_be_bytes());
        header[12..14].copy_from_slice(&0xbedeu16.to_be_bytes());
        header[14..16].copy_from_slice(&2u16.to_be_bytes());
        header[16] = 0x32;
        header[17] = (absolute_send_time >> 16) as u8;
        header[18] = (absolute_send_time >> 8) as u8;
        header[19] = absolute_send_time as u8;
        header[20] = 0x51;
        header[21..23].copy_from_slice(&transport_sequence.to_be_bytes());
        let range = packet.range();
        let payload_start = range.start + 24;
        let payload_end = payload_start + payload_len;
        packet.extend_tail(padding_total + 10)?;
        let storage = packet.storage_mut();
        if padding_random > 0 {
            state
                .rng
                .fill_bytes(&mut storage[payload_end..payload_end + padding_random]);
        }
        let tag_start = payload_end + padding_total;
        storage[tag_start - 1] = padding_total as u8;
        let iv = srtp_iv(config.ssrc, sequence, timestamp);
        let mut cipher = aes_ctr(&self.aes, &iv);
        cipher.apply_keystream(&mut storage[payload_start..tag_start]);
        let mut mac = self.hmac.clone();
        mac.update(&storage[range.start..tag_start]);
        let tag = mac.finalize().into_bytes();
        storage[tag_start..tag_start + 10].copy_from_slice(&tag[..10]);
        Ok(())
    }

    pub fn unwrap(&self, packet: &mut PacketBuf, _mode: ObfsMode) -> Result<u16> {
        let range = packet.range();
        let wire = packet.as_slice();
        if wire.len() < 13 || wire[0] >> 6 != 2 {
            bail!("obfs: not RTP v2");
        }
        let payload_type = wire[1] & 0x7f;
        if !matches!(payload_type, 111 | 96 | 6) {
            bail!("obfs: unsupported payload type {payload_type}");
        }
        let sequence = u16::from_be_bytes([wire[2], wire[3]]);
        let timestamp = u32::from_be_bytes(wire[4..8].try_into()?);
        let ssrc = u32::from_be_bytes(wire[8..12].try_into()?);
        let mut header_len = 12usize + usize::from(wire[0] & 0x0f) * 4;
        if wire.len() < header_len {
            bail!("obfs: packet too short for RTP CSRCs");
        }
        if wire[0] & 0x10 != 0 {
            if wire.len() < header_len + 4 {
                bail!("obfs: packet too short for extension header");
            }
            let extension_words = u16::from_be_bytes([wire[header_len + 2], wire[header_len + 3]]);
            header_len += 4 + usize::from(extension_words) * 4;
        }
        if wire.len() < header_len + 10 {
            bail!("obfs srtp: missing payload or authentication tag");
        }
        let tag_start = range.end - 10;
        let payload_start = range.start + header_len;
        let storage = packet.storage_mut();
        let mut mac = self.hmac.clone();
        mac.update(&storage[range.start..tag_start]);
        if mac
            .verify_truncated_left(&storage[tag_start..range.end])
            .is_err()
        {
            bail!("obfs srtp: authentication failed");
        }
        let iv = srtp_iv(ssrc, sequence, timestamp);
        let mut cipher = aes_ctr(&self.aes, &iv);
        cipher.apply_keystream(&mut storage[payload_start..tag_start]);
        let mut payload_end = tag_start;
        if storage[range.start] & 0x20 != 0 {
            let padding = storage[tag_start - 1] as usize;
            if padding == 0 || padding > tag_start - payload_start {
                bail!("obfs srtp: invalid padding length {padding}");
            }
            payload_end -= padding;
        }
        packet.set_range(payload_start..payload_end)?;
        Ok(sequence)
    }
}

#[inline(always)]
fn aes_ctr(cipher: &aes::Aes128, iv: &[u8; 16]) -> Aes128Ctr128BE {
    Aes128Ctr128BE::from_core(Aes128CtrCore::inner_iv_init(cipher.clone(), iv.into()))
}

fn seeded_rng() -> StdRng {
    static FALLBACK_SEQUENCE: AtomicU64 = AtomicU64::new(0);

    let mut seed = [0u8; 32];
    if getrandom::fill(&mut seed).is_err() {
        let sequence = FALLBACK_SEQUENCE.fetch_add(1, Ordering::Relaxed);
        let time = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos() as u64;
        let base = time ^ sequence.rotate_left(17) ^ u64::from(std::process::id());
        for (index, chunk) in seed.chunks_exact_mut(8).enumerate() {
            let value = base
                .wrapping_add((index as u64).wrapping_mul(0x9e37_79b9_7f4a_7c15))
                .rotate_left((index * 13) as u32);
            chunk.copy_from_slice(&value.to_le_bytes());
        }
    }
    StdRng::from_seed(seed)
}

#[inline(always)]
fn rtp_clock_rate(payload_type: u8) -> u32 {
    if payload_type == 96 { 90_000 } else { 48_000 }
}

#[inline(always)]
fn duration_ticks(elapsed: Duration, clock_rate: u32) -> u32 {
    let rate = u64::from(clock_rate);
    elapsed
        .as_secs()
        .wrapping_mul(rate)
        .wrapping_add(u64::from(elapsed.subsec_nanos()).wrapping_mul(rate) / 1_000_000_000)
        as u32
}

impl ObfsConfig {
    fn initial_sequence(&self, state: &ObfsState, count: u64) -> u16 {
        state.initial_sequence.wrapping_add(count as u16)
    }
}

fn srtp_iv(ssrc: u32, sequence: u16, timestamp: u32) -> [u8; 16] {
    let mut value = [0u8; 16];
    value[..4].copy_from_slice(&ssrc.to_be_bytes());
    value[4..6].copy_from_slice(&sequence.to_be_bytes());
    value[8..12].copy_from_slice(&timestamp.to_be_bytes());
    value
}

pub fn is_rtp_packet(wire: &[u8]) -> bool {
    wire.len() >= 13 && wire[0] >> 6 == 2 && matches!(wire[1] & 0x7f, 111 | 96 | 6)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{packet::PacketPool, wrap::derive_wrap_key};
    use proptest::prelude::*;

    // Authoritative synthetic GETCONF fixture from CSQTT 2.5's server tests.
    // The video variant changes only RTP PT to 96 and recalculates its HMAC;
    // the independently supplied AES ciphertext/IV are identical.
    use crate::wire_protocol::CLIENT_GETCONF_AUDIO_FIXTURE as CSQTT25_AUDIO_FIXTURE;
    const CSQTT25_VIDEO_FIXTURE: &str = "b06077881122334410203040bede0002325566775199aa005c468ea9f0329817714cb0d49b032855ae1b18c3ee81bf342bdd58cd166bdf23a16575b0ca29cb53618501b45b3ae4bb1fda630bb39ce45dbf5dde3073f99f87f2da0010b786f35f61d54f86e98b2d6e";
    const CSQTT25_GETCONF: &[u8] =
        b"GETCONF:46000|wire-device|wire-password|7|wire-salt|1|27|CSQTT-WIRE-2";

    fn roundtrip(mode: ObfsMode) {
        let pool = PacketPool::new(1);
        let mut packet = pool.acquire();
        let payload = b"GETCONF:9000|device|password|7|salt";
        packet.read_area()[..payload.len()].copy_from_slice(payload);
        packet.set_read_len(payload.len()).unwrap();
        let cipher = ObfsCipher::new(derive_wrap_key("password").unwrap()).unwrap();
        let config = ObfsConfig::new(mode);
        let mut state = ObfsState::new();
        cipher.wrap(&mut packet, &config, &mut state).unwrap();
        assert!(is_rtp_packet(packet.as_slice()));
        cipher.unwrap(&mut packet, mode).unwrap();
        assert_eq!(packet.as_slice(), payload);
    }

    fn roundtrip_sized_payload(mode: ObfsMode, length: usize, marker: u8) {
        let pool = PacketPool::new(1);
        let mut packet = pool.acquire();
        let payload = vec![marker; length];
        packet.read_area()[..payload.len()].copy_from_slice(&payload);
        packet.set_read_len(payload.len()).unwrap();
        let cipher = ObfsCipher::new(derive_wrap_key("sized-roundtrip-password").unwrap()).unwrap();
        let mut state = ObfsState::new();
        cipher
            .wrap(&mut packet, &ObfsConfig::new(mode), &mut state)
            .unwrap();
        assert!(is_rtp_packet(packet.as_slice()));
        cipher.unwrap(&mut packet, mode).unwrap();
        assert_eq!(packet.as_slice(), payload);
    }

    macro_rules! sized_roundtrip_case {
        ($name:ident, $mode:expr, $length:expr, $marker:expr) => {
            #[test]
            fn $name() {
                roundtrip_sized_payload($mode, $length, $marker);
            }
        };
    }

    sized_roundtrip_case!(audio_roundtrip_one_byte, ObfsMode::Audio, 1, 0x01);
    sized_roundtrip_case!(audio_roundtrip_fifteen_bytes, ObfsMode::Audio, 15, 0x0f);
    sized_roundtrip_case!(audio_roundtrip_sixty_four_bytes, ObfsMode::Audio, 64, 0x40);
    sized_roundtrip_case!(audio_roundtrip_255_bytes, ObfsMode::Audio, 255, 0xff);
    sized_roundtrip_case!(audio_roundtrip_512_bytes, ObfsMode::Audio, 512, 0x5a);
    sized_roundtrip_case!(audio_roundtrip_1024_bytes, ObfsMode::Audio, 1024, 0xa5);
    sized_roundtrip_case!(audio_roundtrip_1536_bytes, ObfsMode::Audio, 1536, 0x33);
    sized_roundtrip_case!(audio_roundtrip_2048_bytes, ObfsMode::Audio, 2048, 0xcc);
    sized_roundtrip_case!(video_roundtrip_one_byte, ObfsMode::Video, 1, 0x02);
    sized_roundtrip_case!(video_roundtrip_fifteen_bytes, ObfsMode::Video, 15, 0x10);
    sized_roundtrip_case!(video_roundtrip_sixty_four_bytes, ObfsMode::Video, 64, 0x41);
    sized_roundtrip_case!(video_roundtrip_255_bytes, ObfsMode::Video, 255, 0xfe);
    sized_roundtrip_case!(video_roundtrip_512_bytes, ObfsMode::Video, 512, 0x6b);
    sized_roundtrip_case!(video_roundtrip_1024_bytes, ObfsMode::Video, 1024, 0xb6);
    sized_roundtrip_case!(video_roundtrip_1536_bytes, ObfsMode::Video, 1536, 0x44);
    sized_roundtrip_case!(video_roundtrip_2048_bytes, ObfsMode::Video, 2048, 0xdd);

    #[test]
    fn stable_getconf_audio_fixture_matches_server_contract() {
        let payload = b"GETCONF:46000|wire-device|wire-password|7|wire-salt|1|27|CSQTT-WIRE-2";
        let pool = PacketPool::new(1);
        let mut packet = pool.acquire();
        packet.read_area()[..payload.len()].copy_from_slice(payload);
        packet.set_read_len(payload.len()).unwrap();
        let config = ObfsConfig {
            padding_max: 0,
            ssrc: 0x1020_3040,
            payload_type: 111,
            mode: ObfsMode::Audio,
        };
        let mut state = ObfsState {
            count: 0,
            initial_timestamp: 0x1122_3344,
            initial_abs_send_time: 0x0055_6677,
            initial_sequence: 0x7788,
            transport_sequence: 0x99aa,
            started: Instant::now() + Duration::from_secs(1),
            rng: StdRng::seed_from_u64(1),
        };
        ObfsCipher::new(derive_wrap_key("wire-password").unwrap())
            .unwrap()
            .wrap(&mut packet, &config, &mut state)
            .unwrap();
        assert_eq!(hex::encode(packet.as_slice()), CSQTT25_AUDIO_FIXTURE,);
    }

    #[test]
    fn audio_roundtrip() {
        roundtrip(ObfsMode::Audio);
    }

    #[test]
    fn video_roundtrip() {
        roundtrip(ObfsMode::Video);
    }

    #[test]
    fn rtp_clocks_follow_elapsed_media_time() {
        let frame = Duration::from_millis(20);
        assert_eq!(duration_ticks(frame, rtp_clock_rate(111)), 960);
        assert_eq!(duration_ticks(frame, rtp_clock_rate(96)), 1_800);
        assert_eq!(duration_ticks(Duration::from_secs(1), 48_000), 48_000);
        assert_eq!(duration_ticks(Duration::from_secs(1), 90_000), 90_000);
    }

    #[test]
    fn supported_modes_parse() {
        assert_eq!(ObfsMode::parse("audio").unwrap(), ObfsMode::Audio);
        assert_eq!(ObfsMode::parse("video").unwrap(), ObfsMode::Video);
    }

    #[test]
    fn unknown_mode_returns_error() {
        assert!(ObfsMode::parse("invalid").is_err());
    }

    #[test]
    fn empty_payload_returns_error() {
        let pool = PacketPool::new(1);
        let mut packet = pool.acquire();
        let cipher = ObfsCipher::new(derive_wrap_key("password").unwrap()).unwrap();
        let mut state = ObfsState::new();
        assert!(
            cipher
                .wrap(&mut packet, &ObfsConfig::new(ObfsMode::Audio), &mut state)
                .is_err()
        );
    }

    #[test]
    fn unwraps_csqtt25_server_vectors_in_either_media_profile() {
        let cipher = ObfsCipher::new(derive_wrap_key("wire-password").unwrap()).unwrap();
        for encoded in [CSQTT25_AUDIO_FIXTURE, CSQTT25_VIDEO_FIXTURE] {
            for mode in [ObfsMode::Audio, ObfsMode::Video] {
                let wire = hex::decode(encoded).unwrap();
                let pool = PacketPool::new(1);
                let mut packet = pool.acquire();
                packet.read_area()[..wire.len()].copy_from_slice(&wire);
                packet.set_read_len(wire.len()).unwrap();
                assert_eq!(cipher.unwrap(&mut packet, mode).unwrap(), 0x7788);
                assert_eq!(packet.as_slice(), CSQTT25_GETCONF);
            }
        }
    }

    #[test]
    fn csqtt25_server_fixture_rejects_wrong_password() {
        let cipher = ObfsCipher::new(derive_wrap_key("different-password").unwrap()).unwrap();
        for encoded in [CSQTT25_AUDIO_FIXTURE, CSQTT25_VIDEO_FIXTURE] {
            let wire = hex::decode(encoded).unwrap();
            let pool = PacketPool::new(1);
            let mut packet = pool.acquire();
            packet.read_area()[..wire.len()].copy_from_slice(&wire);
            packet.set_read_len(wire.len()).unwrap();
            assert!(cipher.unwrap(&mut packet, ObfsMode::Audio).is_err());
        }
    }

    #[test]
    fn oversized_payload_returns_error() {
        let pool = PacketPool::new(1);
        let mut packet = pool.acquire();
        let length = packet.read_area().len();
        packet.set_read_len(length).unwrap();
        let cipher = ObfsCipher::new(derive_wrap_key("password").unwrap()).unwrap();
        let mut state = ObfsState::new();
        assert!(
            cipher
                .wrap(&mut packet, &ObfsConfig::new(ObfsMode::Video), &mut state)
                .is_err()
        );
    }

    #[test]
    fn audio_wire_rejects_every_single_byte_tamper() {
        let key = derive_wrap_key("tamper-password").unwrap();
        let cipher = ObfsCipher::new(key).unwrap();
        let config = ObfsConfig::new(ObfsMode::Audio);
        let pool = PacketPool::new(1);
        let payload = b"authenticated-audio-payload";
        let wire = {
            let mut packet = pool.acquire();
            packet.read_area()[..payload.len()].copy_from_slice(payload);
            packet.set_read_len(payload.len()).unwrap();
            cipher
                .wrap(&mut packet, &config, &mut ObfsState::new())
                .unwrap();
            packet.as_slice().to_vec()
        };
        for index in 0..wire.len() {
            let mut tampered = wire.clone();
            tampered[index] ^= 1;
            let mut packet = pool.acquire();
            packet.read_area()[..tampered.len()].copy_from_slice(&tampered);
            packet.set_read_len(tampered.len()).unwrap();
            assert!(
                cipher.unwrap(&mut packet, config.mode).is_err(),
                "tamper index {index}"
            );
        }
    }

    #[test]
    fn video_wire_rejects_every_single_byte_tamper() {
        let key = derive_wrap_key("tamper-password").unwrap();
        let cipher = ObfsCipher::new(key).unwrap();
        let config = ObfsConfig::new(ObfsMode::Video);
        let pool = PacketPool::new(1);
        let payload = b"authenticated-video-payload";
        let wire = {
            let mut packet = pool.acquire();
            packet.read_area()[..payload.len()].copy_from_slice(payload);
            packet.set_read_len(payload.len()).unwrap();
            cipher
                .wrap(&mut packet, &config, &mut ObfsState::new())
                .unwrap();
            packet.as_slice().to_vec()
        };
        for index in 0..wire.len() {
            let mut tampered = wire.clone();
            tampered[index] ^= 1;
            let mut packet = pool.acquire();
            packet.read_area()[..tampered.len()].copy_from_slice(&tampered);
            packet.set_read_len(tampered.len()).unwrap();
            assert!(
                cipher.unwrap(&mut packet, config.mode).is_err(),
                "tamper index {index}"
            );
        }
    }

    #[test]
    fn sixty_three_parallel_streams_survive_sustained_roundtrips() {
        let key = derive_wrap_key("parallel-stream-password").unwrap();
        std::thread::scope(|scope| {
            for stream in 0..63u8 {
                scope.spawn(move || {
                    let cipher = ObfsCipher::new(key).unwrap();
                    let config = ObfsConfig::new(if stream % 2 == 0 {
                        ObfsMode::Audio
                    } else {
                        ObfsMode::Video
                    });
                    let pool = PacketPool::new(1);
                    let mut state = ObfsState::new();
                    for sequence in 0..1_000u32 {
                        let payload = [stream, sequence as u8, (sequence >> 8) as u8, 0x5a];
                        let mut packet = pool.acquire();
                        packet.read_area()[..payload.len()].copy_from_slice(&payload);
                        packet.set_read_len(payload.len()).unwrap();
                        cipher.wrap(&mut packet, &config, &mut state).unwrap();
                        cipher.unwrap(&mut packet, config.mode).unwrap();
                        assert_eq!(packet.as_slice(), payload);
                    }
                });
            }
        });
    }

    proptest! {
        #[test]
        fn arbitrary_wire_input_never_panics(wire in proptest::collection::vec(any::<u8>(), 0..=2240)) {
            let cipher = ObfsCipher::new([7u8; WRAP_KEY_LEN]).unwrap();
            for mode in [ObfsMode::Audio, ObfsMode::Video] {
                let pool = PacketPool::new(1);
                let mut packet = pool.acquire();
                packet.read_area()[..wire.len()].copy_from_slice(&wire);
                packet.set_read_len(wire.len()).unwrap();
                let _ = cipher.unwrap(&mut packet, mode);
            }
        }
    }
}
