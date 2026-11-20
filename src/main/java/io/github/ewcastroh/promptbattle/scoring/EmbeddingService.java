package io.github.ewcastroh.promptbattle.scoring;

import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Embeds stored images, cached by (imageId, provider) (SPEC §4.3). */
public final class EmbeddingService {

    private record Key(String imageId, String provider) {
    }

    private final Map<String, EmbeddingProvider> providers = new LinkedHashMap<>();
    private final ImageStore images;
    private final Map<Key, double[]> cache = new ConcurrentHashMap<>();

    public EmbeddingService(List<EmbeddingProvider> providers, ImageStore images) {
        for (EmbeddingProvider p : providers) {
            this.providers.put(p.providerId(), p);
        }
        this.images = images;
    }

    public boolean has(String providerId) {
        return providers.containsKey(providerId);
    }

    public List<String> providerIds() {
        return List.copyOf(providers.keySet());
    }

    public double[] embed(String imageId, String providerId) {
        EmbeddingProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new EmbeddingException("Unknown embedding provider " + providerId);
        }
        Key key = new Key(imageId, providerId);
        double[] cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = images.read(imageId)
                .orElseThrow(() -> new EmbeddingException("Missing image " + imageId));
        double[] vector = provider.embed(bytes, "image/jpeg");
        cache.put(key, vector);
        return vector;
    }

    public void clear() {
        cache.clear();
    }
}
