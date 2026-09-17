use crate::config::Config;
use crate::store::Store;
use crate::upstream::Upstream;

#[derive(Clone)]
pub struct AppState {
    pub config: Config,
    pub store: Store,
    pub upstream: Upstream,
}
