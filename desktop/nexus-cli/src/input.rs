//! Interactive terminal prompts.

use std::io::{self, Write};
use std::sync::Arc;

use nexus_core::{PairingDecision, PairingPrompt, PromptFn};

/// Creates a fresh prompt callback per pairing attempt.
pub type PromptFactory = Arc<dyn Fn() -> PromptFn + Send + Sync>;

/// Prompts the user to compare the verification code and accept or reject.
pub fn interactive_prompts() -> PromptFactory {
    Arc::new(|| {
        Box::new(|prompt: &PairingPrompt| {
            let direction = if prompt.we_initiated {
                "Pairing with"
            } else {
                "Pairing request from"
            };
            println!();
            println!("  ┌─────────────────────────────────────────────────────");
            println!(
                "  │ {direction}: {} ({})",
                prompt.peer.name, prompt.peer.platform
            );
            println!("  │");
            println!("  │   Verification code:   {}", prompt.code);
            println!("  │");
            println!("  │   Confirm this code matches the other device.");
            println!("  └─────────────────────────────────────────────────────");
            let answer = prompt_line("  Accept pairing? [y/N] ");
            if matches!(answer.trim().to_ascii_lowercase().as_str(), "y" | "yes") {
                PairingDecision::Accept
            } else {
                PairingDecision::Reject
            }
        })
    })
}

/// Accepts automatically when the code matches `code`; rejects otherwise.
///
/// Intended for scripted tests, not for normal use.
pub fn auto_prompts(code: String) -> PromptFactory {
    Arc::new(move || nexus_core::pairing::auto_accept(&code))
}

/// Read one line from stdin, trimmed. EOF yields `None`.
pub fn read_line_opt() -> Option<String> {
    use std::io::BufRead;
    let mut line = String::new();
    match io::stdin().lock().read_line(&mut line) {
        Ok(0) => None,
        Ok(_) => Some(line.trim().to_string()),
        Err(_) => None,
    }
}

/// Read one line from stdin, trimmed. EOF yields an empty string.
pub fn read_line() -> String {
    read_line_opt().unwrap_or_default()
}

/// Print a prompt and read one line.
pub fn prompt_line(prompt: &str) -> String {
    print!("{prompt}");
    let _ = io::stdout().flush();
    read_line()
}
