package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public interface ContentAddressedStorage {

    int MAX_OBJECT_LENGTH  = 1024*256;

    default CompletableFuture<Multihash> put(SigningPrivateKeyAndPublicHash writer, byte[] block) {
        return put(writer.publicKeyHash, writer.secret.signatureOnly(block), block);
    }

    default CompletableFuture<Multihash> put(PublicKeyHash writer, byte[] signature, byte[] block) {
        return put(writer, Arrays.asList(signature), Arrays.asList(block))
                .thenApply(hashes -> hashes.get(0));
    }

    default CompletableFuture<Multihash> putRaw(PublicKeyHash writer, byte[] signature, byte[] block) {
        return putRaw(writer, Arrays.asList(signature), Arrays.asList(block))
                .thenApply(hashes -> hashes.get(0));
    }

    CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks);

    CompletableFuture<Optional<CborObject>> get(Multihash object);

    CompletableFuture<List<Multihash>> putRaw(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks);

    CompletableFuture<Optional<byte[]>> getRaw(Multihash object);

    CompletableFuture<List<MultiAddress>> pinUpdate(Multihash existing, Multihash updated);

    CompletableFuture<List<Multihash>> recursivePin(Multihash h);

    CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h);

    CompletableFuture<List<Multihash>> getLinks(Multihash root);

    CompletableFuture<Optional<Integer>> getSize(Multihash block);

    default CompletableFuture<PublicKeyHash> putSigningKey(byte[] signature,
                                                           PublicKeyHash authKeyHash,
                                                           PublicSigningKey newKey) {
        return put(authKeyHash, signature, newKey.toCbor().toByteArray())
                .thenApply(PublicKeyHash::new);
    }

    default PublicKeyHash hashKey(PublicSigningKey key) {
        return new PublicKeyHash(new Cid(1, Cid.Codec.DagCbor, new Multihash(
                            Multihash.Type.sha2_256,
                            Hash.sha256(key.serialize()))));
    }

    default CompletableFuture<PublicKeyHash> putBoxingKey(PublicKeyHash controller, byte[] signature, PublicBoxingKey key) {
        return put(controller, signature, key.toCbor().toByteArray())
                .thenApply(PublicKeyHash::new);
    }

    default CompletableFuture<Optional<PublicSigningKey>> getSigningKey(PublicKeyHash hash) {
        return get(hash.hash)
                .thenApply(opt -> Optional.ofNullable(opt).orElse(Optional.empty()).map(PublicSigningKey::fromCbor));
    }

    default CompletableFuture<Optional<PublicBoxingKey>> getBoxingKey(PublicKeyHash hash) {
        return get(hash.hash)
                .thenApply(opt -> Optional.ofNullable(opt).orElse(Optional.empty()).map(PublicBoxingKey::fromCbor));
    }

    default CompletableFuture<Long> getRecursiveBlockSize(Multihash block) {
        return getLinks(block).thenCompose(links -> {
            List<CompletableFuture<Long>> subtrees = links.stream().map(this::getRecursiveBlockSize).collect(Collectors.toList());
            return getSize(block)
                    .thenCompose(sizeOpt -> {
                        CompletableFuture<Long> reduced = Futures.reduceAll(subtrees,
                                0L, (t, fut) -> fut.thenApply(x -> x + t), (a, b) -> a + b);
                        return reduced.thenApply(sum -> sum + sizeOpt.orElse(0));
                    });
        });
    }

    default CompletableFuture<Long> getChangeInContainedSize(MaybeMultihash original, Multihash updated) {
        // TODO optimise cases which result from btree rebalancing or splitting
        if (! original.isPresent())
            return getRecursiveBlockSize(updated);
        return getChangeInContainedSize(original.get(), updated);
    }

    default CompletableFuture<Long> getChangeInContainedSize(Multihash original, Multihash updated) {
        return getLinksAndSize(original)
                .thenCompose(before -> getLinksAndSize(updated).thenCompose(after -> {
                    int objectDelta = after.left - before.left;
                    List<Multihash> onlyBefore = new ArrayList<>(before.right);
                    onlyBefore.removeAll(after.right);
                    List<Multihash> onlyAfter = new ArrayList<>(after.right);
                    onlyAfter.removeAll(before.right);

                    int nPairs = Math.min(onlyBefore.size(), onlyAfter.size());
                    List<Pair<Multihash, Multihash>> pairs = IntStream.range(0, nPairs)
                            .mapToObj(i -> new Pair<>(onlyBefore.get(i), onlyAfter.get(i)))
                            .collect(Collectors.toList());

                    List<Multihash> extraBefore = onlyBefore.subList(nPairs, onlyBefore.size());
                    List<Multihash> extraAfter = onlyAfter.subList(nPairs, onlyAfter.size());
                    Function<List<Multihash>, CompletableFuture<Long>> getAllRecursiveSizes =
                            extra -> Futures.reduceAll(extra,
                                    0L,
                                    (s, h) -> getRecursiveBlockSize(h).thenApply(size -> size + s),
                                    (a, b) -> a + b);

                    Function<List<Pair<Multihash, Multihash>>, CompletableFuture<Long>> getSizeDiff =
                            ps -> Futures.reduceAll(ps,
                                    0L,
                                    (s, p) -> getChangeInContainedSize(p.left, p.right).thenApply(size -> size + s),
                                    (a, b) -> a + b);
                    return getAllRecursiveSizes.apply(extraBefore)
                            .thenCompose(priorSize -> getAllRecursiveSizes.apply(extraAfter)
                                    .thenApply(postSize -> postSize - priorSize + objectDelta))
                            .thenCompose(total -> getSizeDiff.apply(pairs).thenApply(res -> res + total));
                }));
    }

    default CompletableFuture<Pair<Integer, List<Multihash>>> getLinksAndSize(Multihash block) {
        return getLinks(block)
                .thenCompose(links -> getSize(block).thenApply(size -> new Pair<>(size.orElse(0), links)));
    }

    class HTTP implements ContentAddressedStorage {

        private final HttpPoster poster;
        private final String apiPrefix = "api/v0/";

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        private static Multihash getObjectHash(Object rawJson) {
            Map json = (Map)rawJson;
            String hash = (String)json.get("Hash");
            if (hash == null)
                hash = (String)json.get("Key");
            return Cid.decode(hash);
        }

        private static String encode(String component) {
            try {
                return URLEncoder.encode(component, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks) {
            return put(writer, signatures, blocks, "cbor");
        }

        @Override
        public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks) {
            return put(writer, signatures, blocks, "raw");
        }

        private CompletableFuture<List<Multihash>> put(PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, String format) {
            return poster.postMultipart(apiPrefix + "block/put?format=" + format
                    + "&writer=" + encode(writer.toString())
                    + "&signatures=" + signatures.stream().map(ArrayOps::bytesToHex).reduce("", (a, b) -> a + "," + b).substring(1), blocks)
                    .thenApply(bytes -> JSONParser.parseStream(new String(bytes))
                            .stream()
                            .map(json -> getObjectHash(json))
                            .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
            return poster.get(apiPrefix + "block/get?stream-channels=true&arg=" + hash.toString())
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(CborObject.fromByteArray(raw)));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
            return poster.get(apiPrefix + "block/get?stream-channels=true&arg=" + hash.toString())
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(raw));
        }

        @Override
        public CompletableFuture<List<Multihash>> recursivePin(Multihash hash) {
            return poster.get(apiPrefix + "pin/add?stream-channels=true&arg=" + hash.toString())
                    .thenApply(this::getPins);
        }

        @Override
        public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash hash) {
            return poster.get(apiPrefix + "pin/rm?stream-channels=true&r=true&arg=" + hash.toString())
                    .thenApply(this::getPins);
        }

        @Override
        public CompletableFuture<List<MultiAddress>> pinUpdate(Multihash existing, Multihash updated) {
            return poster.get(apiPrefix + "pin/update?stream-channels=true&arg=" + existing.toString() + "&arg=" + updated + "&unpin=false")
                    .thenApply(this::getMultiAddr);
        }

        private List<Multihash> getPins(byte[] raw) {
            Map res = (Map)JSONParser.parse(new String(raw));
            List<String> pins = (List<String>)res.get("Pins");
            return pins.stream().map(Cid::decode).collect(Collectors.toList());
        }

        private List<MultiAddress> getMultiAddr(byte[] raw) {
            Map res = (Map)JSONParser.parse(new String(raw));
            List<String> pins = (List<String>)res.get("Pins");
            return pins.stream().map(MultiAddress::new).collect(Collectors.toList());
        }

        @Override
        public CompletableFuture<List<Multihash>> getLinks(Multihash block) {
            return poster.get(apiPrefix + "refs?arg=" + block.toString())
                    .thenApply(raw -> JSONParser.parseStream(new String(raw))
                            .stream()
                            .map(obj -> (String) (((Map) obj).get("Ref")))
                            .map(Cid::decode)
                            .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
            return poster.get(apiPrefix + "block/stat?stream-channels=true&arg=" + block.toString())
                    .thenApply(raw -> Optional.of((Integer)((Map)JSONParser.parse(new String(raw))).get("Size")));
        }
    }
}
