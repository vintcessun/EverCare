//! Redemption codes.
//!
//! A code is what a buyer gets in exchange for money, until a real payment
//! channel (Google Play / App Store / WeChat Pay) is wired up. Three properties
//! matter, and each one is a thing that would otherwise go wrong:
//!
//! * **The duration lives on the code, not in the request.** The client used to
//!   send `months`, which meant anyone holding a one-month code could ask for
//!   thirty-six. What was bought is a property of the code.
//! * **Single use.** Codes are sold individually, so a reusable code is one
//!   screenshot away from being free for everyone.
//! * **Unguessable.** 128 hex characters, from the OS random source. Short or
//!   structured codes invite someone to write a loop against `/redeem`.
//!
//! Only the SHA-256 of each code is stored, exactly like device tokens: the data
//! file is a list of what has been sold, never a list of working codes.

use chrono::{DateTime, Utc};
use rand::RngCore;
use serde::{Deserialize, Serialize};

/// Characters in a generated code. 128 hex chars is 512 bits of entropy, and
/// looks like a hash, which is the point: there is nothing in it to guess at.
pub const CODE_LEN: usize = 128;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RedeemCode {
    /// SHA-256 of the code. The plaintext exists only in the operator's hands.
    pub code_hash: String,
    /// How many months this code is worth. Set when the code is minted.
    pub months: i64,
    /// Free-text note from whoever minted it, e.g. which batch it belongs to.
    #[serde(default)]
    pub note: String,
    pub created_at: DateTime<Utc>,
    /// The device that used it, once it has been used.
    #[serde(default)]
    pub used_by: Option<String>,
    #[serde(default)]
    pub used_at: Option<DateTime<Utc>>,
}

impl RedeemCode {
    pub fn is_used(&self) -> bool {
        self.used_by.is_some()
    }
}

/// Generates a fresh code. Returned once, in plaintext, to the operator.
pub fn generate_code() -> String {
    // 64 bytes -> 128 hex characters.
    let mut bytes = [0u8; CODE_LEN / 2];
    rand::thread_rng().fill_bytes(&mut bytes);
    hex::encode(bytes)
}

/// Normalises user input before hashing.
///
/// An elderly user reading a code off a slip of paper will produce stray spaces
/// and the occasional capital letter; neither should mean "invalid code".
pub fn normalise(code: &str) -> String {
    code.trim()
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .collect::<String>()
        .to_lowercase()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn generated_codes_are_128_hex_characters() {
        let code = generate_code();
        assert_eq!(code.len(), CODE_LEN);
        assert!(code.chars().all(|c| c.is_ascii_hexdigit()));
        assert!(code.chars().all(|c| !c.is_ascii_uppercase()));
    }

    #[test]
    fn generated_codes_do_not_repeat() {
        let codes: HashSet<String> = (0..500).map(|_| generate_code()).collect();
        assert_eq!(codes.len(), 500);
    }

    #[test]
    fn input_is_forgiving_about_spacing_and_case() {
        let code = generate_code();
        let upper = code.to_uppercase();
        let spaced = format!("  {}-{}  ", &upper[..64], &upper[64..]);

        assert_eq!(normalise(&spaced), code);
        assert_eq!(normalise(&code), code);
    }
}
