use crate::config::Config;
use crate::quota::Quota;
use crate::store::Store;
use crate::upstream::Upstream;

#[derive(Clone)]
pub struct AppState {
    pub config: Config,
    pub store: Store,
    pub upstream: Upstream,
    pub quota: Quota,
}
