package com.eu.habbo.messages.incoming.users;

import com.eu.habbo.Emulator;
import com.eu.habbo.messages.incoming.MessageHandler;
import com.eu.habbo.messages.outgoing.generic.alerts.GenericAlertComposer;
import com.eu.habbo.messages.outgoing.users.UserProfileComposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class SaveUserBannerEvent extends MessageHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SaveUserBannerEvent.class);
    private static final int MAX_URL_LENGTH = 2048;

    @Override
    public void handle() throws Exception {
        String link = this.packet.readString();
        if (!isValidUrl(link)) {
            this.client.sendResponse(new GenericAlertComposer("Enter a valid absolute HTTP or HTTPS image URL (up to 2048 characters)."));
            return;
        }

        int userId = this.client.getHabbo().getHabboInfo().getId();
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO users_banners (user_id, link_image) VALUES (?, ?) " +
                             "ON DUPLICATE KEY UPDATE link_image = VALUES(link_image)")) {
            statement.setInt(1, userId);
            statement.setString(2, link);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to save profile banner for user {}", userId, e);
            this.client.sendResponse(new GenericAlertComposer("The banner could not be saved. Please try again."));
            return;
        }

        // Refresh the authenticated player's profile immediately; the composer reads the saved value.
        this.client.sendResponse(new UserProfileComposer(this.client.getHabbo(), this.client));
    }

    @Override
    public int getRatelimit() {
        return 1000;
    }

    private static boolean isValidUrl(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH || !value.equals(value.trim())) {
            return false;
        }

        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && uri.getHost() != null &&
                    ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception ignored) {
            return false;
        }
    }
}
