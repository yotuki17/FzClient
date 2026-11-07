package dev.fzkc.fzhud.config;

/**
 * Not a setting: starts a page of a module with many settings. The settings screen shows one tab per page
 * along the top and only the current page's settings below them.
 */
public class PageSetting extends HeaderSetting {
    public PageSetting(String key, String label) {
        super(key, label);
    }
}
