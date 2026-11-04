package dev.fzkc.fzhud.config;

/** Title of one module inside a page that shows several modules (Client Settings): its name and ON/OFF switch. */
public class ModuleHeaderSetting extends HeaderSetting {
    public final Module module;

    public ModuleHeaderSetting(Module module) {
        super("h_" + module.id, module.name);
        this.module = module;
    }
}
