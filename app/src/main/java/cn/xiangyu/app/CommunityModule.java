package cn.xiangyu.app;

/**
 * Contract reserved for the future moderated message board.
 * The entry stays hidden until both a reviewed client release and the server flag enable it.
 */
final class CommunityModule {
    static final String STATUS_PATH = "/api/v1/community/status";
    static final boolean CLIENT_RELEASE_ENABLED = false;

    private CommunityModule() { }

    static boolean shouldShowEntry(boolean serverEnabled) {
        return CLIENT_RELEASE_ENABLED && serverEnabled;
    }
}
