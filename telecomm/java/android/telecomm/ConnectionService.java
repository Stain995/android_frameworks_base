/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telecomm;

import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.DisconnectCause;

import com.android.internal.telecomm.ICallService;
import com.android.internal.telecomm.RemoteServiceCallback;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link android.app.Service} that provides telephone connections to
 * processes running on an Android device.
 */
public abstract class ConnectionService extends CallService {
    // Flag controlling whether PII is emitted into the logs
    private static final boolean PII_DEBUG = Log.isLoggable(android.util.Log.DEBUG);
    private static final Connection NULL_CONNECTION = new Connection() {};

    // Mappings from Connections to IDs as understood by the current CallService implementation
    private final Map<String, Connection> mConnectionById = new HashMap<>();
    private final Map<Connection, String> mIdByConnection = new HashMap<>();
    private final RemoteConnectionManager mRemoteConnectionManager = new RemoteConnectionManager();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private SimpleResponse<Uri, List<PhoneAccount>> mAccountLookupResponse;
    private Uri mAccountLookupHandle;
    private boolean mAreAccountsInitialized = false;

    /**
     * A callback for providing the resuilt of creating a connection.
     */
    public interface OutgoingCallResponse<CONNECTION> {
        /**
         * Tells Telecomm that an attempt to place the specified outgoing call succeeded.
         *
         * @param request The original request.
         * @param connection The connection.
         */
        void onSuccess(ConnectionRequest request, CONNECTION connection);

        /**
         * Tells Telecomm that an attempt to place the specified outgoing call failed.
         *
         * @param request The original request.
         * @param code An integer code indicating the reason for failure.
         * @param msg A message explaining the reason for failure.
         */
        void onFailure(ConnectionRequest request, int code, String msg);

        /**
         * Tells Telecomm to cancel the call.
         *
         * @param request The original request.
         */
        void onCancel(ConnectionRequest request);
    }

    private final Connection.Listener mConnectionListener = new Connection.Listener() {
        @Override
        public void onStateChanged(Connection c, int state) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set state %s %s", id, Connection.stateToString(state));
            switch (state) {
                case Connection.State.ACTIVE:
                    getAdapter().setActive(id);
                    break;
                case Connection.State.DIALING:
                    getAdapter().setDialing(id);
                    break;
                case Connection.State.DISCONNECTED:
                    // Handled in onDisconnected()
                    break;
                case Connection.State.HOLDING:
                    getAdapter().setOnHold(id);
                    break;
                case Connection.State.NEW:
                    // Nothing to tell Telecomm
                    break;
                case Connection.State.RINGING:
                    getAdapter().setRinging(id);
                    break;
            }
        }

        /** {@inheritDoc} */
        @Override
        public void onFeaturesChanged(Connection c, int features) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set features %d", features);
            getAdapter().setFeatures(id, features);
        }

        @Override
        public void onDisconnected(Connection c, int cause, String message) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter set disconnected %d %s", cause, message);
            getAdapter().setDisconnected(id, cause, message);
        }

        @Override
        public void onHandleChanged(Connection c, Uri newHandle) {
            // TODO: Unsupported yet
        }

        @Override
        public void onAudioStateChanged(Connection c, CallAudioState state) {
            // TODO: Unsupported yet
        }

        @Override
        public void onSignalChanged(Connection c, Bundle details) {
            // TODO: Unsupported yet
        }

        @Override
        public void onDestroyed(Connection c) {
            removeConnection(c);
        }

        @Override
        public final void onPostDialWait(Connection c, String remaining) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter onPostDialWait %s, %s", c, remaining);
            getAdapter().onPostDialWait(id, remaining);
        }

        @Override
        public void onRequestingRingback(Connection c, boolean ringback) {
            String id = mIdByConnection.get(c);
            Log.d(this, "Adapter onRingback %b", ringback);
            getAdapter().setRequestingRingback(id, ringback);
        }

        @Override
        public void onConferenceCapableChanged(Connection c, boolean isConferenceCapable) {
            String id = mIdByConnection.get(c);
            getAdapter().setCanConference(id, isConferenceCapable);
        }

        /** ${inheritDoc} */
        @Override
        public void onParentConnectionChanged(Connection c, Connection parent) {
            String id = mIdByConnection.get(c);
            String parentId = parent == null ? null : mIdByConnection.get(parent);
            getAdapter().setIsConferenced(id, parentId);
        }

        @Override
        public void onSetCallVideoProvider(Connection c, CallVideoProvider callVideoProvider) {
            String id = mIdByConnection.get(c);
            getAdapter().setCallVideoProvider(id, callVideoProvider);
        }
    };

    /** @hide */
    @Override
    public final void call(final CallInfo callInfo) {
        Log.d(this, "call %s", callInfo);
        onCreateConnections(
                new ConnectionRequest(
                        callInfo.getId(),
                        callInfo.getHandle(),
                        callInfo.getExtras()),
                new OutgoingCallResponse<Connection>() {
                    @Override
                    public void onSuccess(ConnectionRequest request, Connection connection) {
                        Log.d(this, "adapter handleSuccessfulOutgoingCall %s",
                                callInfo.getId());
                        getAdapter().handleSuccessfulOutgoingCall(callInfo.getId());
                        addConnection(callInfo.getId(), connection);
                    }

                    @Override
                    public void onFailure(ConnectionRequest request, int code, String msg) {
                        getAdapter().handleFailedOutgoingCall(request, code, msg);
                    }

                    @Override
                    public void onCancel(ConnectionRequest request) {
                        getAdapter().cancelOutgoingCall(callInfo.getId());
                    }
                }
        );
    }

    /** @hide */
    @Override
    public final void abort(String callId) {
        Log.d(this, "abort %s", callId);
        findConnectionForAction(callId, "abort").abort();
    }

    /** @hide */
    @Override
    public final void setIncomingCallId(final String callId, Bundle extras) {
        Log.d(this, "setIncomingCallId %s %s", callId, extras);
        onCreateIncomingConnection(
                new ConnectionRequest(
                        callId,
                        null,  // TODO: Can we obtain this from "extras"?
                        extras),
                new Response<ConnectionRequest, Connection>() {
                    @Override
                    public void onResult(ConnectionRequest request, Connection... result) {
                        if (result != null && result.length != 1) {
                            Log.d(this, "adapter handleFailedOutgoingCall %s", callId);
                            getAdapter().handleFailedOutgoingCall(
                                    request,
                                    DisconnectCause.ERROR_UNSPECIFIED,
                                    "Created " + result.length + " Connections, expected 1");
                            for (Connection c : result) {
                                c.abort();
                            }
                        } else {
                            addConnection(callId, result[0]);
                            Log.d(this, "adapter notifyIncomingCall %s", callId);
                            // TODO: Uri.EMPTY is because CallInfo crashes when Parceled with a
                            // null URI ... need to fix that at its cause!
                            getAdapter().notifyIncomingCall(new CallInfo(
                                    callId,
                                    connectionStateToCallState(result[0].getState()),
                                    request.getHandle() /* result[0].getHandle() == null
                                            ? Uri.EMPTY : result[0].getHandle() */));
                        }
                    }

                    @Override
                    public void onError(ConnectionRequest request, int code, String msg) {
                        Log.d(this, "adapter failed setIncomingCallId %s %d %s",
                                request, code, msg);
                    }
                }
        );
    }

    /** @hide */
    @Override
    public final void answer(String callId) {
        Log.d(this, "answer %s", callId);
        findConnectionForAction(callId, "answer").answer();
    }

    /** @hide */
    @Override
    public final void reject(String callId) {
        Log.d(this, "reject %s", callId);
        findConnectionForAction(callId, "reject").reject();
    }

    /** @hide */
    @Override
    public final void disconnect(String callId) {
        Log.d(this, "disconnect %s", callId);
        findConnectionForAction(callId, "disconnect").disconnect();
    }

    /** @hide */
    @Override
    public final void hold(String callId) {
        Log.d(this, "hold %s", callId);
        findConnectionForAction(callId, "hold").hold();
    }

    /** @hide */
    @Override
    public final void unhold(String callId) {
        Log.d(this, "unhold %s", callId);
        findConnectionForAction(callId, "unhold").unhold();
    }

    /** @hide */
    @Override
    public final void playDtmfTone(String callId, char digit) {
        Log.d(this, "playDtmfTone %s %c", callId, digit);
        findConnectionForAction(callId, "playDtmfTone").playDtmfTone(digit);
    }

    /** @hide */
    @Override
    public final void stopDtmfTone(String callId) {
        Log.d(this, "stopDtmfTone %s", callId);
        findConnectionForAction(callId, "stopDtmfTone").stopDtmfTone();
    }

    /** @hide */
    @Override
    public final void onAudioStateChanged(String callId, CallAudioState audioState) {
        Log.d(this, "onAudioStateChanged %s %s", callId, audioState);
        findConnectionForAction(callId, "onAudioStateChanged").setAudioState(audioState);
    }

    /** @hide */
    @Override
    public final void conference(final String conferenceCallId, String callId) {
        Log.d(this, "conference %s, %s", conferenceCallId, callId);

        Connection connection = findConnectionForAction(callId, "conference");
        if (connection == NULL_CONNECTION) {
            Log.w(this, "Connection missing in conference request %s.", callId);
            return;
        }

        onCreateConferenceConnection(conferenceCallId, connection,
                new Response<String, Connection>() {
                    /** ${inheritDoc} */
                    @Override
                    public void onResult(String ignored, Connection... result) {
                        Log.d(this, "onCreateConference.Response %s", (Object[]) result);
                        if (result != null && result.length == 1) {
                            Connection conferenceConnection = result[0];
                            if (!mIdByConnection.containsKey(conferenceConnection)) {
                                Log.v(this, "sending new conference call %s", conferenceCallId);
                                getAdapter().addConferenceCall(conferenceCallId);
                                addConnection(conferenceCallId, conferenceConnection);
                            }
                        }
                    }

                    /** ${inheritDoc} */
                    @Override
                    public void onError(String request, int code, String reason) {
                        // no-op
                    }
                });
    }

    /** @hide */
    @Override
    public final void splitFromConference(String callId) {
        Log.d(this, "splitFromConference(%s)", callId);

        Connection connection = findConnectionForAction(callId, "splitFromConference");
        if (connection == NULL_CONNECTION) {
            Log.w(this, "Connection missing in conference request %s.", callId);
            return;
        }

        // TODO(santoscordon): Find existing conference call and invoke split(connection).
    }

    /** @hide */
    @Override
    public final void onPostDialContinue(String callId, boolean proceed) {
        Log.d(this, "onPostDialContinue(%s)", callId);

        Connection connection = findConnectionForAction(callId, "onPostDialContinue");
        if (connection == NULL_CONNECTION) {
            Log.w(this, "Connection missing in post-dial request %s.", callId);
            return;
        }
        connection.onPostDialContinue(proceed);
    }

    /**
     * @hide
     */
    @Override
    protected final void onAdapterAttached(CallServiceAdapter adapter) {
        if (mAreAccountsInitialized) {
            // No need to query again if we already did it.
            return;
        }

        getAdapter().queryRemoteConnectionServices(new RemoteServiceCallback.Stub() {
            @Override
            public void onResult(
                    final List<ComponentName> componentNames,
                    final List<IBinder> callServices) {
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        for (int i = 0; i < componentNames.size() && i < callServices.size(); i++) {
                            mRemoteConnectionManager.addConnectionService(
                                    componentNames.get(i),
                                    ICallService.Stub.asInterface(callServices.get(i)));
                        }
                        mAreAccountsInitialized = true;
                        Log.d(this, "remote call services found: " + callServices);
                        maybeRespondToAccountLookup();
                    }
                });
            }

            @Override
            public void onError() {
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        mAreAccountsInitialized = true;
                        maybeRespondToAccountLookup();
                    }
                });
            }
        });
    }

    public final void lookupRemoteAccounts(
            Uri handle, SimpleResponse<Uri, List<PhoneAccount>> response) {
        mAccountLookupResponse = response;
        mAccountLookupHandle = handle;
        maybeRespondToAccountLookup();
    }

    public final void maybeRespondToAccountLookup() {
        if (mAreAccountsInitialized && mAccountLookupResponse != null) {
            mAccountLookupResponse.onResult(
                    mAccountLookupHandle,
                    mRemoteConnectionManager.getAccounts(mAccountLookupHandle));

            mAccountLookupHandle = null;
            mAccountLookupResponse = null;
        }
    }

    public final void createRemoteOutgoingConnection(
            ConnectionRequest request,
            OutgoingCallResponse<RemoteConnection> response) {
        mRemoteConnectionManager.createOutgoingConnection(request, response);
    }

    /**
     * Returns all connections currently associated with this connection service.
     */
    public final Collection<Connection> getAllConnections() {
        return mConnectionById.values();
    }

    /**
     * Create a Connection given a request.
     *
     * @param request Data encapsulating details of the desired Connection.
     * @param callback A callback for providing the result.
     */
    protected void onCreateConnections(
            ConnectionRequest request,
            OutgoingCallResponse<Connection> callback) {}

    /**
     * Returns a new or existing conference connection when the the user elects to convert the
     * specified connection into a conference call. The specified connection can be any connection
     * which had previously specified itself as conference-capable including both simple connections
     * and connections previously returned from this method.
     *
     * @param connection The connection from which the user opted to start a conference call.
     * @param token The token to be passed into the response callback.
     * @param callback The callback for providing the potentially-new conference connection.
     */
    protected void onCreateConferenceConnection(
            String token,
            Connection connection,
            Response<String, Connection> callback) {}

    /**
     * Create a Connection to match an incoming connection notification.
     *
     * IMPORTANT: If the incoming connection has a phone number (or other handle) that the user
     * is not supposed to be able to see (e.g. it is PRESENTATION_RESTRICTED), then a compliant
     * ConnectionService implementation MUST NOT reveal this phone number as part of the Intent
     * it sends to notify Telecomm of an incoming connection.
     *
     * @param request Data encapsulating details of the desired Connection.
     * @param callback A callback for providing the result.
     */
    protected void onCreateIncomingConnection(
            ConnectionRequest request,
            Response<ConnectionRequest, Connection> callback) {}

    /**
     * Notifies that a connection has been added to this connection service and sent to Telecomm.
     *
     * @param connection The connection which was added.
     */
    protected void onConnectionAdded(Connection connection) {}

    /**
     * Notified that a connection has been removed from this connection service.
     *
     * @param connection The connection which was removed.
     */
    protected void onConnectionRemoved(Connection connection) {}

    static String toLogSafePhoneNumber(String number) {
        // For unknown number, log empty string.
        if (number == null) {
            return "";
        }

        if (PII_DEBUG) {
            // When PII_DEBUG is true we emit PII.
            return number;
        }

        // Do exactly same thing as Uri#toSafeString() does, which will enable us to compare
        // sanitized phone numbers.
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c == '-' || c == '@' || c == '.') {
                builder.append(c);
            } else {
                builder.append('x');
            }
        }
        return builder.toString();
    }

    private CallState connectionStateToCallState(int connectionState) {
        switch (connectionState) {
            case Connection.State.NEW:
                return CallState.NEW;
            case Connection.State.RINGING:
                return CallState.RINGING;
            case Connection.State.DIALING:
                return CallState.DIALING;
            case Connection.State.ACTIVE:
                return CallState.ACTIVE;
            case Connection.State.HOLDING:
                return CallState.ON_HOLD;
            case Connection.State.DISCONNECTED:
                return CallState.DISCONNECTED;
            default:
                Log.wtf(this, "Unknown Connection.State %d", connectionState);
                return CallState.NEW;
        }
    }

    private void addConnection(String callId, Connection connection) {
        mConnectionById.put(callId, connection);
        mIdByConnection.put(connection, callId);
        connection.addConnectionListener(mConnectionListener);
        onConnectionAdded(connection);
    }

    private void removeConnection(Connection connection) {
        connection.removeConnectionListener(mConnectionListener);
        mConnectionById.remove(mIdByConnection.get(connection));
        mIdByConnection.remove(connection);
        onConnectionRemoved(connection);
    }

    private Connection findConnectionForAction(String callId, String action) {
        if (mConnectionById.containsKey(callId)) {
            return mConnectionById.get(callId);
        }
        Log.w(this, "%s - Cannot find Connection %s", action, callId);
        return NULL_CONNECTION;
    }

    /**
     * Handles changes to the features of a connection.
     * Features are defined in {@link android.telecomm.CallFeatures} and are passed in as a
     * bit-mask.
     *
     * @param callId The call to set the features for.
     * @param features The new features of the call.
     */
    @Override
    public final void onFeaturesChanged(String callId, int features) {
        Log.d(this, "onFeaturesChanged %s %d", callId, features);
        findConnectionForAction(callId, "onFeaturesChanged").setFeatures(features);
    }
}
