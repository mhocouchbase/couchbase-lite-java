//
// Document.java
//
// Copyright (c) 2017 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite;

import android.support.annotation.NonNull;

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.core.C4Document;
import com.couchbase.lite.internal.fleece.FLDict;
import com.couchbase.lite.internal.fleece.FLEncoder;
import com.couchbase.lite.internal.fleece.FLSliceResult;
import com.couchbase.lite.internal.fleece.MRoot;


/**
 * Readonly version of the Document.
 */
public class Document implements DictionaryInterface, Iterable<String> {
    //---------------------------------------------
    // Load LiteCore library and its dependencies
    //---------------------------------------------
    static { NativeLibraryLoader.load(); }


    /**
     * !!! This code is from v1.x. Replace with c4rev_getGeneration().
     */
    static long generationFromRevID(String revID) {
        long generation = 0;
        final long length = Math.min(revID == null ? 0 : revID.length(), 9);
        for (int i = 0; i < length; ++i) {
            final char c = revID.charAt(i);
            if (Character.isDigit(c)) { generation = 10 * generation + Character.getNumericValue(c); }
            else if (c == '-') { return generation; }
            else { break; }
        }
        return 0;
    }


    //---------------------------------------------
    // member variables
    //---------------------------------------------
    private final Object lock = new Object(); // lock for thread-safety

    private String id;
    private Database database;

    private Dictionary internalDict;
    private C4Document c4doc;
    private FLDict data;
    private MRoot root;

    //---------------------------------------------
    // Constructors
    //---------------------------------------------

    protected Document(Database database, String id, C4Document c4doc) {
        this.database = database;
        this.id = id;
        setC4Document(c4doc);
    }

    Document(Database database, String id, FLDict body) {
        this(database, id, (C4Document) null);
        this.data = body;
        updateDictionary();
    }

    Document(Database database, String id, boolean includeDeleted) throws CouchbaseLiteException {
        this(database, id, (C4Document) null);
        final C4Document doc;
        try {
            if ((this.database == null) || (this.database.getC4Database() == null)) {
                throw new IllegalStateException("Database must be non-null and open");
            }
            doc = this.database.getC4Database().get(getId(), true);
        }
        catch (LiteCoreException e) {
            throw CBLStatus.convertException(e);
        }

        if (!includeDeleted && (doc.getFlags() & C4Constants.DocumentFlags.DELETED) != 0) {
            doc.retain();
            doc.release(); // doc is not retained before set.
            throw new CouchbaseLiteException(CBLError.Domain.CBLITE, CBLError.Code.NOT_FOUND);
        }

        // NOTE: c4doc should not be null.
        setC4Document(doc);
    }

    //---------------------------------------------
    // API - public methods
    //---------------------------------------------

    /**
     * return the document's ID.
     *
     * @return the document's ID
     */
    @NonNull
    public String getId() { return id; }

    /**
     * Get the document's revision id. The revision id in the Document class is a constant, while
     * the revision id in the MutableDocument class is not. The newly created Document will have
     * a null revision id. The revision id in MutableDocument will be updated on save.
     * The revision id format is opaque, which means its format has no meaning and shouldn't be
     * parsed to get information.
     *
     * @return the document's revision id
     */
    public String getRevisionID() {
        synchronized (lock) { return c4doc == null ? null : c4doc.getSelectedRevID(); }
    }
    /**
     * Return the sequence number of the document in the database.
     * This indicates how recently the document has been changed: every time any document is updated,
     * the database assigns it the next sequential sequence number. Thus, if a document's `sequence`
     * property changes that means it's been changed (on-disk); and if one document's `sequence`
     * is greater than another's, that means it was changed more recently.
     *
     * @return the sequence number of the document in the database.
     */
    public long getSequence() {
        synchronized (lock) { return c4doc != null ? c4doc.getSelectedSequence() : 0; }
    }

    /**
     * Return a mutable copy of the document
     *
     * @return the MutableDocument instance
     */
    @NonNull
    public MutableDocument toMutable() { return new MutableDocument(this); }

    /**
     * Gets a number of the entries in the dictionary.
     *
     * @return the number of entries in the dictionary.
     */
    @Override
    public int count() { return internalDict.count(); }

    //---------------------------------------------
    // API - Implemenents ReadOnlyDictionaryInterface
    //---------------------------------------------

    /**
     * Get an List containing all keys, or an empty List if the document has no properties.
     *
     * @return all keys
     */
    @NonNull
    @Override
    public List<String> getKeys() { return internalDict.getKeys(); }

    /**
     * Gets a property's value as an object. The object types are Blob, Array,
     * Dictionary, Number, or String based on the underlying data type; or nil if the
     * property value is null or the property doesn't exist.
     *
     * @param key the key.
     * @return the object value or nil.
     */
    @Override
    public Object getValue(@NonNull String key) { return internalDict.getValue(key); }

    /**
     * Gets a property's value as a String.
     * Returns null if the value doesn't exist, or its value is not a String.
     *
     * @param key the key
     * @return the String or null.
     */
    @Override
    public String getString(@NonNull String key) { return internalDict.getString(key); }

    /**
     * Gets a property's value as a Number.
     * Returns null if the value doesn't exist, or its value is not a Number.
     *
     * @param key the key
     * @return the Number or nil.
     */
    @Override
    public Number getNumber(@NonNull String key) { return internalDict.getNumber(key); }

    /**
     * Gets a property's value as an int.
     * Floating point values will be rounded. The value `true` is returned as 1, `false` as 0.
     * Returns 0 if the value doesn't exist or does not have a numeric value.
     *
     * @param key the key
     * @return the int value.
     */
    @Override
    public int getInt(@NonNull String key) { return internalDict.getInt(key); }

    /**
     * Gets a property's value as an long.
     * Floating point values will be rounded. The value `true` is returned as 1, `false` as 0.
     * Returns 0 if the value doesn't exist or does not have a numeric value.
     *
     * @param key the key
     * @return the long value.
     */
    @Override
    public long getLong(@NonNull String key) { return internalDict.getLong(key); }

    /**
     * Gets a property's value as an float.
     * Integers will be converted to float. The value `true` is returned as 1.0, `false` as 0.0.
     * Returns 0.0 if the value doesn't exist or does not have a numeric value.
     *
     * @param key the key
     * @return the float value.
     */
    @Override
    public float getFloat(@NonNull String key) { return internalDict.getFloat(key); }

    /**
     * Gets a property's value as an double.
     * Integers will be converted to double. The value `true` is returned as 1.0, `false` as 0.0.
     * Returns 0.0 if the property doesn't exist or does not have a numeric value.
     *
     * @param key the key
     * @return the double value.
     */
    @Override
    public double getDouble(@NonNull String key) { return internalDict.getDouble(key); }

    /**
     * Gets a property's value as a boolean. Returns true if the value exists, and is either `true`
     * or a nonzero number.
     *
     * @param key the key
     * @return the boolean value.
     */
    @Override
    public boolean getBoolean(@NonNull String key) { return internalDict.getBoolean(key); }

    /**
     * Gets a property's value as a Blob.
     * Returns null if the value doesn't exist, or its value is not a Blob.
     *
     * @param key the key
     * @return the Blob value or null.
     */
    @Override
    public Blob getBlob(@NonNull String key) { return internalDict.getBlob(key); }

    /**
     * Gets a property's value as a Date.
     * JSON does not directly support dates, so the actual property value must be a string, which is
     * then parsed according to the ISO-8601 date format (the default used in JSON.)
     * Returns null if the value doesn't exist, is not a string, or is not parseable as a date.
     * NOTE: This is not a generic date parser! It only recognizes the ISO-8601 format, with or
     * without milliseconds.
     *
     * @param key the key
     * @return the Date value or null.
     */
    @Override
    public Date getDate(@NonNull String key) { return internalDict.getDate(key); }

    /**
     * Get a property's value as a Array, which is a mapping object of an array value.
     * Returns null if the property doesn't exists, or its value is not an Array.
     *
     * @param key the key
     * @return The Array object or null.
     */
    @Override
    public Array getArray(@NonNull String key) { return internalDict.getArray(key); }

    /**
     * Get a property's value as a Dictionary, which is a mapping object of
     * a Dictionary value.
     * Returns null if the property doesn't exists, or its value is not a Dictionary.
     *
     * @param key the key
     * @return The Dictionary object or null.
     */
    @Override
    public Dictionary getDictionary(@NonNull String key) { return internalDict.getDictionary(key); }

    /**
     * Gets content of the current object as an Map. The values contained in the returned
     * Map object are all JSON based values.
     *
     * @return the Map object representing the content of the current object in the JSON format.
     */
    @NonNull
    @Override
    public Map<String, Object> toMap() { return internalDict.toMap(); }

    /**
     * Tests whether a property exists or not.
     * This can be less expensive than getValue(String),
     * because it does not have to allocate an Object for the property value.
     *
     * @param key the key
     * @return the boolean value representing whether a property exists or not.
     */
    @Override
    public boolean contains(@NonNull String key) { return internalDict.contains(key); }

    //---------------------------------------------
    // Iterable implementation
    //---------------------------------------------

    /**
     * Gets  an iterator over the keys of the document's properties
     *
     * @return The key iterator
     */
    @NonNull
    @Override
    public Iterator<String> iterator() { return getKeys().iterator(); }

    //---------------------------------------------
    // Override
    //---------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (!(o instanceof Document)) { return false; }

        final Document doc = (Document) o;

        // Step 1: Check Database
        if (database != null ? !database.equalsWithPath(doc.database) : doc.database != null) { return false; }

        // Step 2: Check document ID
        // NOTE id never null?
        if (!id.equals(doc.id)) { return false; }

        // Step 3: Check content
        // NOTE: internalDict never null??
        return internalDict.equals(doc.internalDict);
    }

    @Override
    public int hashCode() {
        // NOTE id and internalDict never null
        int result = database != null && database.getPath() != null ? database.getPath().hashCode() : 0;
        result = 31 * result + id.hashCode();
        result = 31 * result + internalDict.hashCode();
        return result;
    }

    @SuppressWarnings("NoFinalizer")
    @Override
    protected void finalize() throws Throwable {
        free();
        super.finalize();
    }

    //---------------------------------------------
    // Package level access
    //---------------------------------------------

    boolean isMutable() { return false; }

    boolean isEmpty() { return internalDict.isEmpty(); }

    boolean isNewDocument() { return getRevisionID() == null; }

    /**
     * Return whether the document exists in the database.
     *
     * @return true if exists, false otherwise.
     */
    boolean exists() { return c4doc != null && c4doc.exists(); }

    /**
     * Return whether the document is deleted
     *
     * @return true if deleted, false otherwise
     */
    boolean isDeleted() {
        synchronized (lock) { return (c4doc != null) && c4doc.deleted(); }
    }

    // TODO: c4rev_getGeneration
    long generation() {
        synchronized (lock) { return generationFromRevID(getRevisionID()); }
    }

    void setId(String id) { this.id = id; }

    Database getDatabase() { return database; }
    void setDatabase(Database database) { this.database = database; }

    Dictionary getContent() { return internalDict; }
    void setContent(Dictionary content) { internalDict = content; }

    C4Document getC4doc() {
        synchronized (lock) { return c4doc; }
    }

    void replaceC4Document(C4Document c4doc) {
        synchronized (lock) {
            final C4Document oldDoc = this.c4doc;
            this.c4doc = c4doc;
            if (oldDoc != this.c4doc) {
                if (this.c4doc != null) { this.c4doc.retain(); }
                // oldDoc should be retained.
                if (oldDoc != null) { oldDoc.release(); }
            }
        }
    }

    boolean selectConflictingRevision() throws LiteCoreException {
        synchronized (lock) {
            boolean foundConflict = false;
            if (c4doc != null) {
                while (!foundConflict) {
                    try { c4doc.selectNextLeafRevision(true, true); }
                    catch (LiteCoreException e) {
                        // NOTE: other platforms checks if return value from c4doc_selectNextLeafRevision() is false
                        if (e.code == 0) { break; }
                        else { throw e; }
                    }
                    foundConflict = c4doc.isSelectedRevFlags(C4Constants.RevisionFlags.IS_CONFLICT);
                }
            }
            if (foundConflict) { setC4Document(c4doc); }
            return foundConflict;
        }
    }

    FLSliceResult encode() throws LiteCoreException {
        final FLEncoder encoder = getDatabase().getC4Database().getSharedFleeceEncoder();
        try {
            encoder.setExtraInfo(this);
            internalDict.encodeTo(encoder);
            return encoder.finish2();
        }
        finally {
            encoder.setExtraInfo(null);
            encoder.reset();
        }
    }

    //---------------------------------------------
    // Private access
    //---------------------------------------------

    // Sets c4doc and updates my root dictionary
    private void setC4Document(C4Document c4doc) {
        synchronized (lock) {
            replaceC4Document(c4doc);
            data = null;
            if (c4doc != null && !c4doc.deleted()) { data = c4doc.getSelectedBody2(); }
            updateDictionary();
        }
    }

    private void updateDictionary() {
        if (data != null) {
            root = new MRoot(new DocContext(database, c4doc), data.toFLValue(), isMutable());
            synchronized (database.getLock()) { internalDict = (Dictionary) root.asNative(); }
        }
        else {
            root = null;
            internalDict = isMutable() ? new MutableDictionary() : new Dictionary();
        }
    }

    private void free() {
        root = null;
        if (c4doc != null) {
            c4doc.release(); // c4doc should be retained.
            c4doc = null;
        }
    }
}
