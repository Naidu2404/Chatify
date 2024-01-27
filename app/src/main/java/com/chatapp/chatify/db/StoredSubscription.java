package com.chatapp.chatify.db;

import com.chatapp.tinodesdk.LocalData;
import com.chatapp.tinodesdk.model.Subscription;

/**
 * Subscription record
 */
public class StoredSubscription implements LocalData.Payload {
    public long id;
    public long topicId;
    public long userId;
    public BaseDb.Status status;

    public static long getId(Subscription sub) {
        StoredSubscription ss = (StoredSubscription) sub.getLocal();
        return ss == null ? -1 : ss.id;
    }
}
