package com.eu.habbo.habbohotel.permissions;

public enum PermissionSetting {

    DISALLOWED,


    ALLOWED,


    ROOM_OWNER;

    public static PermissionSetting fromString(String value) {
        switch (value) {
            case "1":
                return ALLOWED;
            case "2":
                // This permission is granted only when the caller confirms room ownership.
                return ROOM_OWNER;

        }

        return DISALLOWED;
    }
}
