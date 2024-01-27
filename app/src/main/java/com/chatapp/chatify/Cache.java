package com.chatapp.chatify;

import android.os.Build;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessaging;

import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.chatapp.chatify.db.BaseDb;
import com.chatapp.chatify.media.VxCard;
import com.chatapp.chatify.services.CallConnection;
import com.chatapp.tinodesdk.ComTopic;
import com.chatapp.tinodesdk.FndTopic;
import com.chatapp.tinodesdk.MeTopic;
import com.chatapp.tinodesdk.PromisedReply;
import com.chatapp.tinodesdk.Storage;
import com.chatapp.tinodesdk.Tinode;
import com.chatapp.tinodesdk.model.MsgServerData;
import com.chatapp.tinodesdk.model.MsgServerInfo;
import com.chatapp.tinodesdk.model.PrivateType;
import com.chatapp.tinodesdk.model.ServerMessage;

/**
 * Shared resources.
 */
public class Cache {
    private static final String TAG = "Cache";

    private static final String API_KEY = "AQEAAAABAAD_rAp4DJh05a1HAwFT3A6K";

    private static final Cache sInstance = new Cache();

    private Tinode mTinode = null;

    // Currently active topic.
    private String mTopicSelected = null;

    // Current video call.
    private CallInProgress mCallInProgress = null;

    public static synchronized Tinode getTinode() {
        if (sInstance.mTinode == null) {
            sInstance.mTinode = new Tinode("Tindroid/" + TindroidApp.getAppVersion(), API_KEY,
                    BaseDb.getInstance().getStore(), null);
            sInstance.mTinode.setOsString(Build.VERSION.RELEASE);

            // Default types for parsing Public, Private fields of messages
            sInstance.mTinode.setDefaultTypeOfMetaPacket(VxCard.class, PrivateType.class);
            sInstance.mTinode.setMeTypeOfMetaPacket(VxCard.class);
            sInstance.mTinode.setFndTypeOfMetaPacket(VxCard.class);

            // Set device language
            sInstance.mTinode.setLanguage(Locale.getDefault().toString());

            // Event handlers for video calls.
            sInstance.mTinode.addListener(new Tinode.EventListener() {
                @Override
                public void onDataMessage(MsgServerData data) {
                    if (Cache.getTinode().isMe(data.from)) {
                        return;
                    }
                    String webrtc = data.getStringHeader("webrtc");
                    MsgServerData.WebRTC callState = MsgServerData.parseWebRTC(webrtc);

                    ComTopic topic = (ComTopic) Cache.getTinode().getTopic(data.topic);
                    if (topic == null) {
                        return;
                    }

                    int effectiveSeq = UiUtils.parseSeqReference(data.getStringHeader("replace"));
                    if (effectiveSeq <= 0) {
                        effectiveSeq = data.seq;
                    }
                    // Check if we have a later version of the message (which means the call
                    // has been not yet either accepted or finished).
                    Storage.Message msg = topic.getMessage(effectiveSeq);
                    if (msg != null) {
                        webrtc = msg.getStringHeader("webrtc");
                        if (webrtc != null && MsgServerData.parseWebRTC(webrtc) != callState) {
                            return;
                        }
                    }

                    switch (callState) {
                        case STARTED:
                            CallManager.acceptIncomingCall(TindroidApp.getAppContext(),
                                    data.topic, data.seq, data.getBooleanHeader("aonly"));
                            break;
                        case ACCEPTED:
                        case DECLINED:
                        case MISSED:
                        case DISCONNECTED:
                            CallInProgress call = Cache.getCallInProgress();
                            if (call != null && !call.isOutgoingCall()) {
                                Log.i(TAG, "Dismissing incoming call: topic = " + data.topic + ", seq = " + data.seq);
                                CallManager.dismissIncomingCall(TindroidApp.getAppContext(), data.topic, data.seq);
                            }
                            break;
                        default:
                            break;
                    }

                }

                @Override
                public void onInfoMessage(MsgServerInfo info) {
                    if (MsgServerInfo.parseWhat(info.what) != MsgServerInfo.What.CALL) {
                        return;
                    }

                    CallInProgress call = Cache.getCallInProgress();
                    if (call == null || !call.equals(info.src, info.seq) || !Tinode.TOPIC_ME.equals(info.topic)) {
                        return;
                    }

                    // Dismiss call notification.
                    // Hang-up event received or current user accepted the call from another device.
                    if (MsgServerInfo.parseEvent(info.event) == MsgServerInfo.Event.HANG_UP ||
                            (Cache.getTinode().isMe(info.from) &&
                                    MsgServerInfo.parseEvent(info.event) == MsgServerInfo.Event.ACCEPT)) {
                        CallManager.dismissIncomingCall(TindroidApp.getAppContext(), info.src, info.seq);
                    }
                }
            });

            // Keep in app to prevent garbage collection.
            TindroidApp.retainCache(sInstance);
        }

        FirebaseMessaging fbId = FirebaseMessaging.getInstance();
        //noinspection ConstantConditions: Google lies about getInstance not returning null.
        if (fbId != null) {
            fbId.getToken().addOnSuccessListener(token -> {
                if (sInstance.mTinode != null) {
                    sInstance.mTinode.setDeviceToken(token);
                }
            });
        }
        return sInstance.mTinode;
    }

    // Invalidate existing cache.
    static void invalidate() {
        endCallInProgress();
        setSelectedTopicName(null);
        if (sInstance.mTinode != null) {
            sInstance.mTinode.logout();
            sInstance.mTinode = null;
        }
        FirebaseMessaging.getInstance().deleteToken();
    }

    public static CallInProgress getCallInProgress() {
        return sInstance.mCallInProgress;
    }

    public static void prepareNewCall(@NonNull String topic, int seq, @Nullable CallConnection conn) {
        if (sInstance.mCallInProgress == null) {
            sInstance.mCallInProgress = new CallInProgress(topic, seq, conn);
        } else if (!sInstance.mCallInProgress.equals(topic, seq)) {
            Log.e(TAG, "Inconsistent prepareNewCall\n\tExisting: " +
                    sInstance.mCallInProgress + "\n\tNew: " + topic + ":" + seq);
        }
    }

    public static void setCallActive(String topic, int seqId) {
        if (sInstance.mCallInProgress!= null) {
            sInstance.mCallInProgress.setCallActive(topic, seqId);
        } else {
            Log.e(TAG, "Attempt to mark call active with no configured call");
        }
    }

    public static void setCallConnected() {
        if (sInstance.mCallInProgress != null) {
            sInstance.mCallInProgress.setCallConnected();
        } else {
            Log.e(TAG, "Attempt to mark call connected with no configured call");
        }
    }

    public static void endCallInProgress() {
        if (sInstance.mCallInProgress != null) {
            sInstance.mCallInProgress.endCall();
            sInstance.mCallInProgress = null;
        }
    }

    public static String getSelectedTopicName() {
        return sInstance.mTopicSelected;
    }

    // Save the new topic name. If the old topic is not null, unsubscribe.
    public static void setSelectedTopicName(String topicName) {
        String oldTopic = sInstance.mTopicSelected;
        sInstance.mTopicSelected = topicName;
        if (sInstance.mTinode != null && oldTopic != null && !oldTopic.equals(topicName)) {
            ComTopic topic = (ComTopic) sInstance.mTinode.getTopic(oldTopic);
            if (topic != null) {
                topic.leave();
            }
        }
    }

    // Connect to 'me' topic.
    @SuppressWarnings("unchecked")
    public static PromisedReply<ServerMessage> attachMeTopic(MeTopic.MeListener l) {
        final MeTopic<VxCard> me = getTinode().getOrCreateMeTopic();
        if (l != null) {
            me.setListener(l);
        }

        if (!me.isAttached()) {
            return me.subscribe(null, me
                    .getMetaGetBuilder()
                    .withCred()
                    .withDesc()
                    .withSub()
                    .withTags()
                    .build());
        } else {
            return new PromisedReply<>((ServerMessage) null);
        }
    }

    static PromisedReply<ServerMessage> attachFndTopic(FndTopic.FndListener<VxCard> l) {
        final FndTopic<VxCard> fnd = getTinode().getOrCreateFndTopic();
        if (l != null) {
            fnd.setListener(l);
        }

        if (!fnd.isAttached()) {
            // Don't request anything here.
            return fnd.subscribe(null, null);
        } else {
            return new PromisedReply<>((ServerMessage) null);
        }
    }
}
