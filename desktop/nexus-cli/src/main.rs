//! `nexus-cli` — development and test harness for the Nexus protocol.
//!
//! Runs the full protocol stack in a terminal so discovery, pairing, and the
//! encrypted session can be exercised without a GUI or an Android device.
//!
//! Typical flows:
//!
//! ```text
//! # terminal 1
//! nexus-cli advertise
//!
//! # terminal 2
//! nexus-cli browse
//!
//! # pair two instances
//! nexus-cli pair            # terminal 1: pick the other device
//! nexus-cli pair            # terminal 2: accept the incoming request
//!
//! # reconnect to a paired device
//! nexus-cli connect "My Phone"
//! ```

mod commands;
mod input;
mod node_setup;

use clap::{ArgAction, Parser, Subcommand};
use std::path::PathBuf;

use tracing_subscriber::EnvFilter;

/// Development harness for the Nexus protocol.
#[derive(Debug, Parser)]
#[command(name = "nexus-cli", version, about, long_about = None)]
pub struct Cli {
    /// Data directory (defaults to the platform Nexus config directory).
    #[arg(long, global = true, value_name = "DIR")]
    pub data_dir: Option<PathBuf>,

    /// Device name shown to other devices.
    #[arg(long, global = true, value_name = "NAME")]
    pub name: Option<String>,

    /// Preferred TCP port (0 or omitted = ephemeral port).
    #[arg(long, global = true, value_name = "PORT")]
    pub port: Option<u16>,

    /// Increase log verbosity (-v = debug, -vv = trace).
    #[arg(short, long, global = true, action = ArgAction::Count)]
    pub verbose: u8,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Advertise this device and browse for peers, printing discovery events.
    Browse,

    /// Advertise and accept connections from already-paired devices only.
    Advertise,

    /// Pair with another Nexus device (responds to incoming requests too).
    Pair {
        /// Device name or id to pair with. Omit to pick interactively.
        #[arg(long, value_name = "DEVICE")]
        to: Option<String>,

        /// Accept automatically when the verification code matches this value.
        #[arg(long, value_name = "CODE")]
        code: Option<String>,
    },

    /// Connect to an already-paired device and print incoming messages.
    Connect {
        /// Device name or id to connect to.
        #[arg(value_name = "DEVICE")]
        target: String,
    },

    /// Manage trusted devices.
    Trust {
        #[command(subcommand)]
        cmd: TrustCommand,
    },

    /// Show this device's identity and fingerprint.
    Identity,
}

#[derive(Debug, Subcommand)]
pub enum TrustCommand {
    /// List trusted devices.
    List,
    /// Forget a trusted device.
    Remove {
        /// Device name or id.
        #[arg(value_name = "DEVICE")]
        device: String,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();

    let level = match cli.verbose {
        0 => "info",
        1 => "debug",
        _ => "trace",
    };
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(level)),
        )
        .with_target(false)
        .without_time()
        .init();

    let result = match &cli.command {
        Command::Browse => commands::browse(&cli).await,
        Command::Advertise => commands::advertise(&cli).await,
        Command::Pair { to, code } => commands::pair(&cli, to.clone(), code.clone()).await,
        Command::Connect { target } => commands::connect(&cli, target.clone()).await,
        Command::Trust { cmd } => commands::trust(&cli, cmd).await,
        Command::Identity => commands::identity(&cli).await,
    };

    if let Err(e) = &result {
        eprintln!("\n✗ {e}");
    }
    result
}
