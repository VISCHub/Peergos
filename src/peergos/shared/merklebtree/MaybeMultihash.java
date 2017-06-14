package peergos.shared.merklebtree;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.io.ipfs.cid.*;

public class MaybeMultihash implements Cborable {
    private final Multihash hash;

    public MaybeMultihash(Multihash hash) {
        this.hash = hash;
    }

    public boolean isPresent() {
        return hash != null;
    }

    public Multihash get() {
        if (! isPresent())
            throw new IllegalStateException("hash not present");
        return hash;
    }

    public String toString() {
        return hash != null ? hash.toString() : "EMPTY";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MaybeMultihash that = (MaybeMultihash) o;

        return hash != null ? hash.equals(that.hash) : that.hash == null;
    }

    @Override
    public int hashCode() {
        return hash != null ? hash.hashCode() : 0;
    }

    public static MaybeMultihash fromCbor(CborObject cbor) {
        if (cbor instanceof CborObject.CborNull)
            return MaybeMultihash.EMPTY();

        if (! (cbor instanceof CborObject.CborByteArray))
            throw new IllegalStateException("Incorrect cbor for MaybeMultihash: " + cbor);
        return MaybeMultihash.of(Cid.cast(((CborObject.CborByteArray) cbor).value));
    }

    @Override
    public CborObject toCbor() {
        return isPresent() ? new CborObject.CborByteArray(hash.toBytes()) : new CborObject.CborNull();
    }

    private static MaybeMultihash EMPTY = new MaybeMultihash(null);

    public static MaybeMultihash EMPTY() {
        return EMPTY;
    }

    public static MaybeMultihash of(Multihash hash) {
        return new MaybeMultihash(hash);
    }
}