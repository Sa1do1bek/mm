package com.enterprise.mm.service.attachment;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.config.AttachmentProperties;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class S3AttachmentStorage implements AttachmentStorage {
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final String EMPTY_SHA256 = sha256Hex(new byte[0]);

    private final AttachmentProperties properties;
    private final HttpClient client;

    public S3AttachmentStorage(AttachmentProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public boolean supports(String provider) {
        String value = provider == null ? "" : provider.toUpperCase(Locale.ROOT);
        return "S3".equals(value) || "MINIO".equals(value);
    }

    @Override
    public StoredFile store(MultipartFile file, String storageKey, String contentType) {
        try {
            return store(file.getInputStream().readAllBytes(), storageKey, contentType);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Cannot upload attachment to S3/MinIO");
        }
    }

    @Override
    public StoredFile store(byte[] content, String storageKey, String contentType) {
        try {
            byte[] payload = content == null ? new byte[0] : content;
            String checksum = sha256Hex(payload);
            HttpRequest request = signedRequest("PUT", storageKey, checksum)
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
            sendNoBody(request, "Cannot upload attachment to S3/MinIO");
            return new StoredFile(payload.length, checksum);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Cannot upload attachment to S3/MinIO");
        }
    }

    @Override
    public InputStreamResource read(String storageKey) {
        try {
            HttpRequest request = signedRequest("GET", storageKey, EMPTY_SHA256)
                .GET()
                .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("Cannot download attachment from S3/MinIO");
            }
            return new InputStreamResource(new ByteArrayInputStream(response.body()));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("Cannot download attachment from S3/MinIO");
        }
    }

    @Override
    public void delete(String storageKey) {
        HttpRequest request = signedRequest("DELETE", storageKey, EMPTY_SHA256)
            .DELETE()
            .build();
        sendNoBody(request, "Cannot delete attachment from S3/MinIO");
    }

    private HttpRequest.Builder signedRequest(String method, String storageKey, String payloadHash) {
        validateConfig();
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String shortDate = SHORT_DATE.format(now);
        URI uri = objectUri(storageKey);
        String canonicalUri = uri.getRawPath();
        String host = hostHeader(uri);
        String scope = shortDate + "/" + properties.getS3Region() + "/s3/aws4_request";
        String canonicalRequest = method + "\n"
            + canonicalUri + "\n\n"
            + "host:" + host + "\n"
            + "x-amz-content-sha256:" + payloadHash + "\n"
            + "x-amz-date:" + amzDate + "\n\n"
            + "host;x-amz-content-sha256;x-amz-date\n"
            + payloadHash;
        String stringToSign = "AWS4-HMAC-SHA256\n"
            + amzDate + "\n"
            + scope + "\n"
            + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hmac(signingKey(shortDate), stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + properties.getS3AccessKey() + "/" + scope
            + ", SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=" + signature;

        return HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.getS3RequestTimeoutSeconds()))
            .header("Authorization", authorization)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate);
    }

    private void sendNoBody(HttpRequest request, String failure) {
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(failure + " (HTTP " + response.statusCode() + ")");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(failure);
        }
    }

    private URI objectUri(String storageKey) {
        String endpoint = properties.getS3Endpoint();
        String root = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String encodedKey = encodePath(storageKey);
        if (properties.isS3PathStyle()) {
            return URI.create(root + "/" + encodeSegment(properties.getS3Bucket()) + "/" + encodedKey);
        }
        URI endpointUri = URI.create(root);
        String scheme = endpointUri.getScheme();
        String authority = properties.getS3Bucket() + "." + endpointUri.getAuthority();
        return URI.create(scheme + "://" + authority + "/" + encodedKey);
    }

    private void validateConfig() {
        if (isBlank(properties.getS3Endpoint()) || isBlank(properties.getS3Bucket()) || isBlank(properties.getS3AccessKey()) || isBlank(properties.getS3SecretKey())) {
            throw new BusinessException("S3/MinIO attachment storage is not configured");
        }
    }

    private byte[] signingKey(String shortDate) {
        byte[] kDate = hmac(("AWS4" + properties.getS3SecretKey()).getBytes(StandardCharsets.UTF_8), shortDate);
        byte[] kRegion = hmac(kDate, properties.getS3Region());
        byte[] kService = hmac(kRegion, "s3");
        return hmac(kService, "aws4_request");
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot sign S3 request", ex);
        }
    }

    private static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot calculate SHA-256", ex);
        }
    }

    private static String encodePath(String value) {
        StringBuilder out = new StringBuilder();
        for (String part : value.split("/", -1)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(encodeSegment(part));
        }
        return out.toString();
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~");
    }

    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port < 0 || ("http".equals(uri.getScheme()) && port == 80) || ("https".equals(uri.getScheme()) && port == 443);
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
