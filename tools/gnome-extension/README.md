# Pixelock focus (GNOME Wayland only)

Windows needs none of this. On GNOME Wayland no program may read the focused window, so this
tiny extension answers on the session bus and `pixelock_laptop.py` asks it.

    mkdir -p ~/.local/share/gnome-shell/extensions/pixelock-focus@pixelock.dev
    cp metadata.json extension.js ~/.local/share/gnome-shell/extensions/pixelock-focus@pixelock.dev/
    # Wayland loads new extensions only at login: log out and in, then
    gnome-extensions enable pixelock-focus@pixelock.dev
    gdbus call --session --dest dev.pixelock.Focus --object-path /dev/pixelock/Focus --method dev.pixelock.Focus.Get

The last line should print the focused app. If it fails the tracker logs `read failed` and counts nothing.
