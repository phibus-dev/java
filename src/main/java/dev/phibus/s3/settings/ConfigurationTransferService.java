package dev.phibus.s3.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class ConfigurationTransferService {
    private static final byte[] MAGIC = "EVOS3CFG1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final String REDACTED = "__NOT_EXPORTED__";

    private final BootstrapSettingsStore store;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public ConfigurationTransferService(BootstrapSettingsStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public ExportedConfiguration exportConfiguration(boolean includeSecrets, boolean encrypted, char[] password) {
        if (includeSecrets && !encrypted) {
            throw new IllegalArgumentException("Экспорт секретов разрешён только в зашифрованный файл");
        }
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("format", "EVO.SNT-S3-CONFIG");
        envelope.put("version", "2.1.0");
        envelope.put("exportedAt", Instant.now().toString());
        JsonNode settings = mapper.valueToTree(store.load());
        if (!includeSecrets) redact(settings);
        envelope.set("bootstrapSettings", settings);
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope);
            if (!encrypted) return new ExportedConfiguration(json, "application/json", "evo-snt-s3-config.json");
            requirePassword(password);
            return new ExportedConfiguration(encrypt(json, password), "application/octet-stream", "evo-snt-s3-config.evos3");
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось сформировать экспорт конфигурации", e);
        } finally {
            clear(password);
        }
    }

    public ValidationResult validate(byte[] data, char[] password) {
        try {
            ImportDocument document = parse(data, password);
            return new ValidationResult(true, document.version(), document.encrypted(),
                    "Конфигурация совместима", document.settings().path("postgresql").isObject(),
                    document.settings().path("vault").isObject(), document.settings().path("keycloak").isObject());
        } catch (RuntimeException e) {
            return new ValidationResult(false, null, isEncrypted(data), e.getMessage(), false, false, false);
        } finally {
            clear(password);
        }
    }

    public ImportResult importConfiguration(byte[] data, char[] password) {
        try {
            ImportDocument document = parse(data, password);
            Path backup = backupCurrent();
            BootstrapSettings imported = mapper.treeToValue(document.settings(), BootstrapSettings.class);
            BootstrapSettings merged = mergeRedacted(imported, store.load());
            store.save(merged);
            return new ImportResult(true, backup == null ? null : backup.toString(),
                    "Конфигурация импортирована. Для применения PostgreSQL и Keycloak перезапустите приложение.");
        } catch (IOException e) {
            throw new IllegalArgumentException("Некорректная структура конфигурации", e);
        } finally {
            clear(password);
        }
    }

    private ImportDocument parse(byte[] data, char[] password) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("Файл конфигурации пуст");
        try {
            boolean encrypted = isEncrypted(data);
            byte[] json;
            if (encrypted) {
                requirePassword(password);
                json = decrypt(data, password);
            } else {
                json = data;
            }
            JsonNode root = mapper.readTree(json);
            if (!"EVO.SNT-S3-CONFIG".equals(root.path("format").asText()))
                throw new IllegalArgumentException("Неподдерживаемый формат конфигурации");
            String version = root.path("version").asText();
            if (!(version.startsWith("2.0.") || version.startsWith("2.1.")))
                throw new IllegalArgumentException("Неподдерживаемая версия конфигурации: " + version);
            JsonNode settings = root.path("bootstrapSettings");
            if (!settings.isObject()) throw new IllegalArgumentException("Отсутствует bootstrapSettings");
            return new ImportDocument(version, encrypted, settings);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Неверный пароль или повреждённый зашифрованный файл", e);
        } catch (IOException e) {
            throw new IllegalArgumentException("Некорректный JSON конфигурации", e);
        }
    }

    private Path backupCurrent() throws IOException {
        Path source = store.path();
        if (!Files.exists(source)) return null;
        Path directory = source.getParent().resolve("backups");
        Files.createDirectories(directory);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        Path backup = directory.resolve("config-backup-" + timestamp + ".json");
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        return backup;
    }

    private BootstrapSettings mergeRedacted(BootstrapSettings imported, BootstrapSettings current) {
        JsonNode importedNode = mapper.valueToTree(imported);
        JsonNode currentNode = mapper.valueToTree(current);
        restoreRedacted(importedNode, currentNode);
        try { return mapper.treeToValue(importedNode, BootstrapSettings.class); }
        catch (IOException e) { throw new IllegalArgumentException("Не удалось объединить секретные параметры", e); }
    }

    private static void redact(JsonNode node) {
        if (!node.isObject()) { if (node.isContainerNode()) node.forEach(ConfigurationTransferService::redact); return; }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey().toLowerCase(Locale.ROOT);
            if (name.contains("password") || name.contains("token") || name.contains("secret") || name.contains("accesskey"))
                ((ObjectNode) node).put(field.getKey(), REDACTED);
            else redact(field.getValue());
        }
    }

    private static void restoreRedacted(JsonNode imported, JsonNode current) {
        if (!imported.isObject() || !current.isObject()) return;
        imported.fields().forEachRemaining(field -> {
            JsonNode old = current.get(field.getKey());
            if (field.getValue().isTextual() && REDACTED.equals(field.getValue().asText()) && old != null)
                ((ObjectNode) imported).set(field.getKey(), old);
            else if (old != null) restoreRedacted(field.getValue(), old);
        });
    }

    private byte[] encrypt(byte[] plain, char[] password) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES]; byte[] iv = new byte[IV_BYTES]; random.nextBytes(salt); random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
        cipher.updateAAD(MAGIC);
        byte[] cipherText = cipher.doFinal(plain);
        return ByteBuffer.allocate(MAGIC.length + salt.length + iv.length + cipherText.length)
                .put(MAGIC).put(salt).put(iv).put(cipherText).array();
    }

    private byte[] decrypt(byte[] encoded, char[] password) throws GeneralSecurityException {
        ByteBuffer buffer = ByteBuffer.wrap(encoded); byte[] magic = new byte[MAGIC.length]; buffer.get(magic);
        byte[] salt = new byte[SALT_BYTES]; byte[] iv = new byte[IV_BYTES]; buffer.get(salt); buffer.get(iv);
        byte[] cipherText = new byte[buffer.remaining()]; buffer.get(cipherText);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv)); cipher.updateAAD(MAGIC);
        return cipher.doFinal(cipherText);
    }

    private SecretKey key(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try { return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES"); }
        finally { spec.clearPassword(); }
    }

    private static boolean isEncrypted(byte[] data) {
        if (data == null || data.length < MAGIC.length + SALT_BYTES + IV_BYTES + 16) return false;
        for (int i = 0; i < MAGIC.length; i++) if (data[i] != MAGIC[i]) return false;
        return true;
    }
    private static void requirePassword(char[] password) { if (password == null || password.length < 12) throw new IllegalArgumentException("Пароль должен содержать не менее 12 символов"); }
    private static void clear(char[] value) { if (value != null) java.util.Arrays.fill(value, '\0'); }

    public record ExportedConfiguration(byte[] content, String contentType, String filename) { }
    public record ValidationResult(boolean valid, String version, boolean encrypted, String message,
                                   boolean postgresql, boolean vault, boolean keycloak) { }
    public record ImportResult(boolean success, String backupPath, String message) { }
    private record ImportDocument(String version, boolean encrypted, JsonNode settings) { }
}
