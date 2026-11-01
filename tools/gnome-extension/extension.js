import Gio from 'gi://Gio';
import Meta from 'gi://Meta';
import Extension from 'resource:///org/gnome/shell/extensions/extension.js';

const XML = `<node><interface name="dev.pixelock.Focus">
  <method name="Get"><arg type="s" direction="out"/><arg type="s" direction="out"/></method>
</interface></node>`;

// Answers with (app class, window title) of the focused window. Local session bus only.
export default class PixelockFocus extends Extension {
    enable() {
        this._dbus = Gio.DBusExportedObject.wrapJSObject(XML, {
            Get() {
                const w = global.display.focus_window;
                return w ? [w.get_wm_class() ?? '', w.get_title() ?? ''] : ['', ''];
            },
        });
        this._dbus.export(Gio.DBus.session, '/dev/pixelock/Focus');
        this._own = Gio.bus_own_name_on_connection(Gio.DBus.session, 'dev.pixelock.Focus',
            Gio.BusNameOwnerFlags.NONE, null, null);
    }

    disable() {
        Gio.bus_unown_name(this._own);
        this._dbus.unexport();
        this._dbus = null;
    }
}
