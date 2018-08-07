package com.microsoft.appcenter.analytics;

import com.microsoft.appcenter.utils.AppCenterLog;
import com.microsoft.appcenter.utils.HandlerUtils;
import com.microsoft.appcenter.utils.HashUtils;
import com.microsoft.appcenter.utils.TicketCache;

import java.util.Date;

import static com.microsoft.appcenter.analytics.Analytics.LOG_TAG;

/**
 * Authentication provider to associate logs with user identifier.
 */
public class AuthenticationProvider {

    /**
     * Ratio threshold of expiry time to refresh token a bit before it's expired.
     */
    private static final double REFRESH_THRESHOLD = 0.9;

    /**
     * The type.
     */
    private final Type mType;

    /**
     * The ticket key for this authentication provider.
     */
    private final String mTicketKey;

    /**
     * The ticket key as hash.
     */
    private final String mTicketKeyHash;

    /**
     * The token provider that will be used to get an updated authentication token.
     */
    private final TokenProvider mTokenProvider;

    /**
     * Refresh timer.
     */
    private Runnable mRefreshTimer;

    /**
     * Create a new authentication provider.
     *
     * @param type          The type for the provider.
     * @param ticketKey     The ticket key for the provider.
     * @param tokenProvider The token provider that will be used to get a current authentication token.
     */
    public AuthenticationProvider(Type type, String ticketKey, TokenProvider tokenProvider) {
        mType = type;
        mTicketKey = ticketKey;
        mTicketKeyHash = ticketKey == null ? null : HashUtils.sha256(ticketKey);
        mTokenProvider = tokenProvider;
    }

    /**
     * Get the type of this authentication provider.
     *
     * @return the type.
     */
    Type getType() {
        return mType;
    }

    /**
     * Get the ticket key for this authentication provider.
     *
     * @return the ticket key.
     */
    String getTicketKey() {
        return mTicketKey;
    }

    /**
     * Get the ticket key hash for this authentication provider.
     *
     * @return the ticket key hash.
     */
    String getTicketKeyHash() {
        return mTicketKeyHash;
    }

    /**
     * Token provider object that will be called to provide a current authentication token.
     *
     * @return the token provider.
     */
    TokenProvider getTokenProvider() {
        return mTokenProvider;
    }

    /**
     * Call token provider callback in background.
     */
    void acquireTokenAsync() {
        AppCenterLog.debug(LOG_TAG, "Calling token provider=" + mType + " callback.");
        mTokenProvider.getToken(mTicketKey, new AuthenticationCallback() {

            @Override
            public void onAuthenticationResult(String token, Date expiresAt) {
                handleTokenUpdate(token, expiresAt);
            }
        });
    }

    /**
     * Handle token callback update.
     *
     * @param token     token value.
     * @param expiresAt token expiry date.
     */
    private synchronized void handleTokenUpdate(String token, Date expiresAt) {

        /* Check parameters. */
        AppCenterLog.debug(LOG_TAG, "Got result back from token provider=" + mType);
        if (token == null) {
            AppCenterLog.error(LOG_TAG, "Authentication failed for ticketKey=" + mTicketKey);
            return;
        }
        if (expiresAt == null) {
            AppCenterLog.error(LOG_TAG, "No expiry provided for ticketKey=" + mTicketKey);
            return;
        }

        /* Update cache. */
        TicketCache.putTicket(mTicketKeyHash, token);

        /* Schedule refresh. */
        long refreshTime = (long) ((expiresAt.getTime() - System.currentTimeMillis()) * REFRESH_THRESHOLD);
        AppCenterLog.info(LOG_TAG, "User authenticated for " + refreshTime + " ms. for provider=" + mType);
        mRefreshTimer = new Runnable() {

            @Override
            public void run() {
                acquireTokenAsync();
            }
        };
        HandlerUtils.getMainHandler().postDelayed(mRefreshTimer, refreshTime);
    }

    /**
     * Cancel token refresh timer if any is pending.
     */
    synchronized void stopRefreshing() {
        if (mRefreshTimer != null) {
            HandlerUtils.getMainHandler().removeCallbacks(mRefreshTimer);
            mRefreshTimer = null;
        }
    }

    /**
     * The supported types of the authentication provider.
     */
    public enum Type {

        /**
         * Microsoft authentication.
         */
        MSA
    }

    /**
     * Application callback to request authentication token value.
     */
    public interface TokenProvider {

        /**
         * Implement this method and pass a fresh authentication token using the callback.
         * This will be called 1 time right after registering and also whenever the token is about to expire.
         *
         * @param ticketKey The ticket key that is used to get an updated token.
         * @param callback  callback to provide the result.
         */
        void getToken(String ticketKey, AuthenticationCallback callback);

    }

    /**
     * Authentication callback.
     */
    public interface AuthenticationCallback {

        /**
         * Notify SDK that authentication completed.
         *
         * @param tokenValue token value or null if authentication failed.
         * @param expiresAt  expiry date for token or null if authentication failed.
         */
        void onAuthenticationResult(String tokenValue, Date expiresAt);
    }
}
