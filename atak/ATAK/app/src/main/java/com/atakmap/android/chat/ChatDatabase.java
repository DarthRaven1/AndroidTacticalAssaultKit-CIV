//Reviewed and Updated on 3/13/25
package com.atakmap.android.chat;

import com.atakmap.coremap.io.DatabaseInformation;
import com.atakmap.coremap.io.IOProvider;
import com.atakmap.coremap.io.IOProviderFactory;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import com.atakmap.database.CursorIface;
import com.atakmap.database.DatabaseIface;
import com.atakmap.database.Databases;
import com.atakmap.database.QueryIface;
import com.atakmap.database.StatementIface;

import android.net.Uri;
import android.util.Pair;
import java.util.Set;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.widget.Toast;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.GroupContact;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.maps.MapView;
import com.atakmap.app.R;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import com.atakmap.coremap.locale.LocaleUtil;
import android.database.DatabaseUtils;
import com.atakmap.util.zip.IoUtils;

public class ChatDatabase {
    public static final String TAG = "ChatDatabase";
    public static final int VERSION = 8;

    private static DatabaseIface chatDb;

    private static final File CHAT_DB_FILE2 = FileSystemUtils
            .getItem("Databases/ChatDb2.sqlite");

    static final String TABLE_CHAT = "Chat";
    static final String TABLE_GROUPS = "Groups";
    static final String ARRAY_DELIMITER = ",";

    private static class DBColumn {
        public String key;
        public String type;

        DBColumn(String key, String type) {
            this.key = key;
            this.type = type;
        }
    }

    // By convention, make these match the names of the fields in the Bundle.
    private static final String ID_COL_NAME = "id";
    private static final String CONVO_ID_COL_NAME = "conversationId";
    private static final String CONVO_NAME_COL_NAME = "conversationName";
    private static final String MESSAGE_ID_COL_NAME = "messageId";
    private static final String PROTOCOL_COL_NAME = "protocol";
    private static final String TYPE_COL_NAME = "type";
    private static final String STATUS_COL_NAME = "status";
    private static final String RECEIVE_TIME_COL_NAME = "receiveTime";
    private static final String SENT_TIME_COL_NAME = "sentTime";
    private static final String READ_TIME_COL_NAME = "readTime";
    private static final String SENDER_UID_COL_NAME = "senderUid";
    private static final String MESSAGE_COL_NAME = "message";

    private static final String CREATED_LOCALLY = "createdLocally"; //Expressed as boolean
    private static final String RECIPIENTS = "destinations"; //Expressed as UIDs
    private static final String GROUP_PARENT = "parent";

    private static final String CONTACT_CALLSIGN_COL_NAME = "senderCallsign";

    private static String getBundleNameForColumn(String columnName) {
        return columnName;
    }

    // DB types
    private static final String PK_COL_TYPE = "INTEGER PRIMARY KEY";
    private static final String TEXT_COL_TYPE = "TEXT";
    private static final String INTEGER_COL_TYPE = "INTEGER";

    private static final DBColumn[] CHAT_COLS = {
            new DBColumn(ID_COL_NAME, PK_COL_TYPE),
            new DBColumn(CONVO_ID_COL_NAME, TEXT_COL_TYPE),
            new DBColumn(MESSAGE_ID_COL_NAME, TEXT_COL_TYPE),
            new DBColumn(PROTOCOL_COL_NAME, TEXT_COL_TYPE),
            new DBColumn(TYPE_COL_NAME, TEXT_COL_TYPE),
            new DBColumn(RECEIVE_TIME_COL_NAME, INTEGER_COL_TYPE),
            new DBColumn(SENT_TIME_COL_NAME, INTEGER_COL_TYPE),
            new DBColumn(READ_TIME_COL_NAME, INTEGER_COL_TYPE),
            new DBColumn(SENDER_UID_COL_NAME, TEXT_COL_TYPE),
            new DBColumn(MESSAGE_COL_NAME, TEXT_COL_TYPE),
            new DBColumn(CONTACT_CALLSIGN_COL_NAME, TEXT_COL_TYPE),
            new DBColumn(STATUS_COL_NAME, TEXT_COL_TYPE)
    };

    private static final DBColumn[] GROUP_COLS = {
            new DBColumn(ID_COL_NAME, PK_COL_TYPE),
            new DBColumn(CONVO_ID_COL_NAME, TEXT_COL_TYPE),
            new DBColumn(CONVO_NAME_COL_NAME, TEXT_COL_TYPE),
            new DBColumn(CREATED_LOCALLY, TEXT_COL_TYPE),
            new DBColumn(RECIPIENTS, TEXT_COL_TYPE),
            new DBColumn(GROUP_PARENT, TEXT_COL_TYPE)
    };

    private static ChatDatabase _instance = null;

    /**
     * Get an instance of the ChatDabase for search, retrieval and archive of chat messages.
     * @param ignored no longer used.
     * @return the singleton instance of the ChatDatabase.
     */
    public synchronized static ChatDatabase getInstance(Context ignored) {
        if (_instance == null) {
            _instance = new ChatDatabase();
        }
        return _instance;
    }

    private void initDatabase() {

        final DatabaseIface oldChatDb = chatDb;

        DatabaseInformation dbi = new DatabaseInformation(
                Uri.fromFile(CHAT_DB_FILE2),
                DatabaseInformation.OPTION_RESERVED1
                        | DatabaseInformation.OPTION_ENSURE_PARENT_DIRS);

        DatabaseIface newChatDb = IOProviderFactory.createDatabase(dbi);

        if (newChatDb != null) {

            if (newChatDb.getVersion() != VERSION) {
                Log.d(TAG, "Upgrading from v" + newChatDb.getVersion()
                        + " to v" + VERSION);
                onUpgrade(newChatDb, newChatDb.getVersion(), VERSION);
            }
        } else {
            try {
                final File f = CHAT_DB_FILE2;
                if (!IOProviderFactory.renameTo(f,
                        new File(CHAT_DB_FILE2 + ".corrupt."
                                + new CoordinatedTime().getMilliseconds()))) {
                    Log.d(TAG, "could not move corrupt db out of the way");
                } else {
                    Log.d(TAG,
                            "default chat database corrupted, move out of the way: "
                                    + f);
                }
            } catch (Exception ignored) {
            }
            newChatDb = IOProviderFactory.createDatabase(dbi);
            if (newChatDb != null) {
                Log.d(TAG, "Upgrading from v" + newChatDb.getVersion()
                        + " to v" + VERSION);
                onUpgrade(newChatDb, newChatDb.getVersion(), VERSION);
            }
        }

        // swap only after the newChatDb is good to go.
        chatDb = newChatDb;

        try {
            if (oldChatDb != null)
                oldChatDb.close();
        } catch (Exception ignored) {
        }

    }

    private ChatDatabase() {

        initDatabase();

    }

    void close() {
        try {
            chatDb.close();
        } catch (Exception ignored) {
        }
    }

    private void onCreate(DatabaseIface db) {
        createTable(db, TABLE_CHAT, CHAT_COLS);
        createTable(db, TABLE_GROUPS, GROUP_COLS);
    }

    private void createTable(DatabaseIface db, String tableName,
                             DBColumn[] columns) {
        StringBuilder createGroupTable = new StringBuilder("CREATE TABLE "
                + tableName + " (");
        String delim = "";
        for (DBColumn col : columns) {
            createGroupTable.append(delim).append(col.key).append(" ")
                    .append(col.type);
            delim = ", ";
        }
        createGroupTable.append(")");
        db.execute(createGroupTable.toString(), null);
    }

    private void onUpgrade(DatabaseIface db, int oldVersion, int newVersion) {
        // Drop older table if existed
        switch (oldVersion) {
            //wasn't implemented before so just drop the tables and recreate
            case 4:
                db.execute("DROP TABLE IF EXISTS " + "Contacts", null); //drop the contact table since we don't use that anymore
                db.execute("ALTER TABLE " + TABLE_CHAT + " ADD COLUMN "
                        + CONTACT_CALLSIGN_COL_NAME + " " + TEXT_COL_TYPE
                        + " DEFAULT ''", null);
            case 5:
                // Add parent column to groups
                db.execute("ALTER TABLE " + TABLE_GROUPS + " ADD COLUMN "
                        + GROUP_PARENT + " " + TEXT_COL_TYPE
                        + " DEFAULT ''", null);
            case 6:
                // Add status column to chat
                db.execute("ALTER TABLE " + TABLE_CHAT + " ADD COLUMN "
                        + STATUS_COL_NAME + " " + TEXT_COL_TYPE
                        + " DEFAULT ''", null);
                break;
            case 7:
                // Add read time column to chat
                db.execute("ALTER TABLE " + TABLE_CHAT + " ADD COLUMN "
                        + READ_TIME_COL_NAME + " " + INTEGER_COL_TYPE, null);
                break;
            default:
                db.execute("DROP TABLE IF EXISTS " + TABLE_CHAT, null);
                db.execute("DROP TABLE IF EXISTS " + TABLE_GROUPS, null);
                onCreate(db);
        }
        db.setVersion(VERSION);
    }

    void onDowngrade(DatabaseIface db, int oldVersion, int newVersion) {
        db.execute("DROP TABLE IF EXISTS " + TABLE_CHAT, null);
        db.execute("DROP TABLE IF EXISTS " + TABLE_GROUPS, null);
        // Create tables again
        onCreate(db);
        db.setVersion(VERSION);
    }

    /**
     * Ability to take a correctly formatted Chat Bundle and add it to the ChatDatabase.
     * @param chatMessage a bundle created from ChatMessage.toBundle() or a bundle containing the
     *                    following keys - "conversationName", "conversationId", "messageId",
     *                    "senderUid", "senderCallsign", "parent", "paths", "deleteChild",
     *                    "groupOwner", "message", status
     * @return a list of longs, but effectively a single row based on the Chat Message bundle.
     */
    public List<Long> addChat(Bundle chatMessage) {
        Log.d(TAG, "adding chat to DB.");

        // Populate ContentValues
        ContentValues chatValues = new ContentValues();
        for (DBColumn dbCol : CHAT_COLS) {
            String dbColName = dbCol.key;
            String bundleKey = getBundleNameForColumn(dbColName);
            String dataType = dbCol.type;
            if (TEXT_COL_TYPE.equals(dataType)) {
                String dataFromBundle = chatMessage.getString(bundleKey);

                if (dbColName != null && dataFromBundle != null) {
                    chatValues.put(dbColName, dataFromBundle);
                }
            } else if (INTEGER_COL_TYPE.equals(dataType)) {
                Long dataFromBundle = chatMessage.getLong(bundleKey, -1);
                if (dataFromBundle < 0)
                    dataFromBundle = null;
                if (dbColName != null && dataFromBundle != null) {
                    chatValues.put(dbColName, dataFromBundle);
                }
            } // ignore other types, including PK
        }
        ContentValues groupValues = new ContentValues();
        for (DBColumn dbColumn : GROUP_COLS) {
            String dbColName = dbColumn.key;
            String bundleKey = getBundleNameForColumn(dbColName);
            String dataType = dbColumn.type;
            if (TEXT_COL_TYPE.equals(dataType)) {
                String dataFromBundle;
                if (!bundleKey.equals(RECIPIENTS))
                    dataFromBundle = chatMessage.getString(bundleKey);
                else
                    dataFromBundle = convertStringArrayToString(chatMessage
                            .getStringArray(bundleKey));
                if (dbColName != null && dataFromBundle != null) {
                    groupValues.put(dbColName, dataFromBundle);
                }
            } else if (INTEGER_COL_TYPE.equals(dataType)) {
                Long dataFromBundle = chatMessage.getLong(bundleKey, -1);
                if (dataFromBundle < 0)
                    dataFromBundle = null;
                if (dbColName != null && dataFromBundle != null) {
                    groupValues.put(dbColName, dataFromBundle);
                }
            }
        }

        // Add to DB
        long id = -1;
        String convId = groupValues.getAsString(CONVO_ID_COL_NAME);
        long groupId = getGroupIndex(convId);
        DatabaseIface db;
        try {
            String msgId = chatValues.getAsString(MESSAGE_ID_COL_NAME);
            Bundle existingMsg = getChatMessage(msgId);
            db = chatDb;
            if (existingMsg != null) {

                String v = parseForUpdate(chatValues);
                StatementIface stmt = null;
                try {
                    stmt = db.compileStatement("UPDATE " + TABLE_CHAT + " SET "
                            + v + " WHERE " + MESSAGE_ID_COL_NAME
                            + "=?");
                    stmt.bind(1, msgId);
                    stmt.execute();
                } finally {
                    if (stmt != null)
                        stmt.close();
                }

                id = existingMsg.getLong(ID_COL_NAME);
            } else {
                Pair<String, String[]> v = parseForInsert(chatValues);
                StatementIface stmt = null;
                try {
                    stmt = db.compileStatement("INSERT INTO " + TABLE_CHAT + "("
                            + v.first + ")" + " VALUES " + "("
                            + formWildcard(v.second) + ")");
                    for (int i = 0; i < v.second.length; ++i)
                        stmt.bind(i + 1, v.second[i]);

                    stmt.execute();
                    id = Databases.lastInsertRowId(db);
                } finally {
                    if (stmt != null)
                        stmt.close();
                }
            }
            //check to make sure it's a group that should be persisted (ie group name doesn't
            // equal the UID)  All streaming is a special case.
            Contacts cts = Contacts.getInstance();
            String convName = groupValues.getAsString(CONVO_NAME_COL_NAME);
            Contact existing = cts.getContactByUuid(convId);
            if (isUserGroup(convName, convId)) {
                // Make sure parent groups exist
                String selfUID = MapView.getDeviceUid();
                String sender = chatValues.getAsString(SENDER_UID_COL_NAME);
                String destinations = groupValues.getAsString(RECIPIENTS);
                boolean local = sender != null
                        && sender.equals(selfUID)
                        && !destinations.contains(convId);
                // If the group exists this should take priority
                if (GroupContact.isGroup(existing))
                    local = ((GroupContact) existing).isUserCreated()
                            || convId.equals(Contacts.USER_GROUPS);
                HierarchyParser parser = new HierarchyParser(chatMessage);
                if (!parser.build() && groupId == -1) {
                    // Legacy user group
                    groupValues.put(CREATED_LOCALLY, String.valueOf(local));
                    Pair<String, String[]> v = parseForInsert(groupValues);
                    StatementIface stmt = null;
                    try {
                        stmt = db.compileStatement(
                                "INSERT INTO " + TABLE_GROUPS + "("
                                        + v.first + ")" + " VALUES " + "("
                                        + formWildcard(v.second) + ")");
                        for (int i = 0; i < v.second.length; ++i)
                            stmt.bind(i + 1, v.second[i]);

                        stmt.execute();
                        groupId = Databases.lastInsertRowId(db);
                    } finally {
                        if (stmt != null)
                            stmt.close();
                    }
                }
            }
            String deleteUID = chatMessage.getString(
                    "deleteChild", null);
            if (deleteUID != null) {
                Contact del = cts.getContactByUuid(deleteUID);
                if (isUserGroup(del.getName(), del.getUID()))
                    removeGroupTree(del);
            }
        } catch (SQLiteException e) {
            final MapView mv = MapView.getMapView();
            if (mv != null) {
                mv.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(mv.getContext(), mv.getContext()
                                .getString(R.string.chat_text14)
                                + mv.getContext().getString(
                                        R.string.clear_db_file),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
            Log.e(TAG, "Experienced an issue with the SQL Query.  " +
                    "Clear your DB file if this continues", e);
        }

        ArrayList<Long> ids = new ArrayList<>();

        // Add the row ID to the chatMessage bundle
        if (id != -1) {
            chatMessage.putLong("id", id);
            chatMessage.putLong("groupId", groupId);

            ids.add(id);
            ids.add(groupId);
        }

        return ids;
    }

    /**
     * Turns a ContentValues class into a string in the form "(key=value, key1=value1,...,keyn=valuen)"
     * @param cv the ContentValues used to parse for the update.
     */
    private String parseForUpdate(final ContentValues cv) {
        StringBuilder kRet = new StringBuilder();
        final Set<String> keys = cv.keySet();
        for (String key : keys) {
            String value = cv.getAsString(key);
            if (kRet.length() != 0)
                kRet.append(",");
            kRet.append(key).append("=?");
        }
        return kRet.toString();
    }

    // TODO - Use CL's suggestion about the Map.
    // check out the class com.atakmap.database.android.BindArgument
    //you can return a LinkedHashMap<String, BindArgument> instead of a pair, where String is column name (used to build SQL) and the bind arguments are the args (edited)
    // there's a static function on BindArgument that takes a collection of them, compiles a statement, binds the arguemtns and executes it

    private void dumbBind(final StatementIface stmt, final String[] args) {
    }

    private String formWildcard(final String[] args) {
        StringBuilder ret = new StringBuilder();
        for (int i = 0; i < args.length; ++i) {
            if (i != 0)
                ret.append(",");
            ret.append("?");
        }
        return ret.toString();
    }

    private Pair<String, String[]> parseForInsert(ContentValues cv) {
        StringBuilder kRet = new StringBuilder();
        List<String> l = new ArrayList<>();
        final Set<String> keys = cv.keySet();
        for (String key : keys) {
            String value = cv.getAsString(key);
            if (value != null) {
                if (kRet.length() != 0)
                    kRet.append(",");
                kRet.append(key);
                l.add(value);
            }
        }
        String[] vRet = new String[l.size()];
        l.toArray(vRet);
        return new Pair<>(kRet.toString(), vRet);
    }

    private class HierarchyParser {

        private final Contacts _cts;
        private final Contact _userGroups;
        private final String _selfUID;
        private final String _senderUID;
        private final Contact _
