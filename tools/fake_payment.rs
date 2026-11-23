//! Test tool, not shipped: prints a signed transaction that pays ADDRESS from a coin that does not exist.
use neb_chain::OutPoint;
use neb_chain::tx::LOCK_SINGLE;
use neb_wallet::wallet::WalletKey;
use neb_wallet::{ADDRESS_PREFIX, OwnedUtxo, Wallet, decode_address, parse_amount, pay};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let lock = decode_address(ADDRESS_PREFIX, &args[1]).expect("address");
    let amount = parse_amount(&args[2]).expect("amount");
    let mut wallet = Wallet::empty("");
    wallet.keys = vec![WalletKey::generate()];
    let coin = OwnedUtxo {
        outpoint: OutPoint { txid: [4u8; 32], index: 0 },
        amount: amount + 1_000_000,
        lock_type: LOCK_SINGLE,
        lock_data: wallet.keys[0].lock_data,
        height: 1,
        coinbase: false,
    };
    let change = wallet.keys[0].lock_data;
    let (tx, _) = pay(&wallet, &[coin], &[(lock, amount)], &[7u8; 32], change).expect("payment");
    println!("{}", tx.encode().iter().map(|b| format!("{b:02x}")).collect::<String>());
}
