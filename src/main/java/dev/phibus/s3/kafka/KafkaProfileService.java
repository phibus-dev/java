package dev.phibus.s3.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.phibus.s3.settings.BootstrapSecretCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KafkaProfileService {
    private final JdbcTemplate jdbc;
    private final BootstrapSecretCodec secretCodec;
    private final ObjectMapper objectMapper;

    private static final Set<String> MANAGED_PROPERTIES = Set.of(
            "bootstrap.servers", "client.id", "security.protocol", "sasl.mechanism", "sasl.jaas.config",
            "ssl.truststore.type", "ssl.truststore.certificates", "session.timeout.ms");

    public KafkaProfileService(JdbcTemplate jdbc, BootstrapSecretCodec secretCodec, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.secretCodec = secretCodec;
        this.objectMapper = objectMapper;
    }

    public List<Profile> list() {
        return jdbc.query("""
                SELECT id, name, bootstrap_servers, security_protocol, sasl_mechanism, username,
                       credentials_source, vault_secret_path, password_field, password_env,
                       ca_certificate_path, default_topic, client_id_prefix, session_timeout_ms, custom_properties, is_default,
                       (password_encrypted IS NOT NULL AND password_encrypted <> '') AS password_configured,
                       created_at, updated_at
                  FROM kafka_profile ORDER BY is_default DESC, name
                """, this::map);
    }

    public Profile get(UUID id) {
        List<Profile> result = jdbc.query("""
                SELECT id, name, bootstrap_servers, security_protocol, sasl_mechanism, username,
                       credentials_source, vault_secret_path, password_field, password_env,
                       ca_certificate_path, default_topic, client_id_prefix, session_timeout_ms, custom_properties, is_default,
                       (password_encrypted IS NOT NULL AND password_encrypted <> '') AS password_configured,
                       created_at, updated_at FROM kafka_profile WHERE id = ?
                """, this::map, id);
        if (result.isEmpty()) throw new IllegalArgumentException("Kafka profile not found: " + id);
        return result.getFirst();
    }

    public Profile defaultProfile() { return list().stream().filter(Profile::defaultProfile).findFirst().orElse(null); }

    @Transactional
    public Profile create(ProfileRequest request) {
        validate(request, false, null);
        UUID id = UUID.randomUUID();
        if (request.defaultProfile()) clearDefault();
        String encryptedPassword = encryptedPasswordForCreate(request);
        jdbc.update("""
                INSERT INTO kafka_profile(id, name, bootstrap_servers, security_protocol, sasl_mechanism,
                    username, credentials_source, vault_secret_path, password_field, password_env,
                    password_encrypted, ca_certificate_path, default_topic, client_id_prefix, session_timeout_ms, custom_properties, is_default)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, request.name().trim(), request.bootstrapServers().trim(), normalizedProtocol(request.securityProtocol()),
                blankToNull(request.saslMechanism()), blankToNull(request.username()), normalizedSource(request.credentialsSource()),
                blankToNull(request.vaultSecretPath()), defaultValue(request.passwordField(), "password"), blankToNull(request.passwordEnv()),
                encryptedPassword, blankToNull(request.caCertificatePath()), blankToNull(request.defaultTopic()),
                defaultValue(request.clientIdPrefix(), "evo-snt"), normalizedSessionTimeout(request.sessionTimeoutMs()),
                writeCustomProperties(request.customProperties()), request.defaultProfile());
        return get(id);
    }

    @Transactional
    public Profile update(UUID id, ProfileRequest request) {
        Profile existing = get(id);
        validate(request, true, existing);
        if (request.defaultProfile()) clearDefault();
        String source = normalizedSource(request.credentialsSource());
        String encryptedPassword = encryptedPasswordForUpdate(id, source, request.password());
        jdbc.update("""
                UPDATE kafka_profile SET name=?, bootstrap_servers=?, security_protocol=?, sasl_mechanism=?, username=?,
                       credentials_source=?, vault_secret_path=?, password_field=?, password_env=?, password_encrypted=?,
                       ca_certificate_path=?, default_topic=?, client_id_prefix=?, session_timeout_ms=?, custom_properties=?, is_default=?, updated_at=CURRENT_TIMESTAMP WHERE id=?
                """, request.name().trim(), request.bootstrapServers().trim(), normalizedProtocol(request.securityProtocol()),
                blankToNull(request.saslMechanism()), blankToNull(request.username()), source,
                blankToNull(request.vaultSecretPath()), defaultValue(request.passwordField(), "password"), blankToNull(request.passwordEnv()),
                encryptedPassword, blankToNull(request.caCertificatePath()), blankToNull(request.defaultTopic()),
                defaultValue(request.clientIdPrefix(), "evo-snt"), normalizedSessionTimeout(request.sessionTimeoutMs()),
                writeCustomProperties(request.customProperties()), request.defaultProfile(), id);
        return get(id);
    }

    /** Synchronizes a non-secret profile definition from coordinator to an agent. */
    public Profile upsertRuntimeProfile(Profile profile) {
        if (profile == null) throw new IllegalArgumentException("Kafka runtime profile is required");
        jdbc.update("""
                INSERT INTO kafka_profile(id, name, bootstrap_servers, security_protocol, sasl_mechanism, username,
                    credentials_source, vault_secret_path, password_field, password_env, ca_certificate_path,
                    default_topic, client_id_prefix, session_timeout_ms, custom_properties, is_default, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, bootstrap_servers=EXCLUDED.bootstrap_servers,
                    security_protocol=EXCLUDED.security_protocol, sasl_mechanism=EXCLUDED.sasl_mechanism,
                    username=EXCLUDED.username, credentials_source=EXCLUDED.credentials_source,
                    vault_secret_path=EXCLUDED.vault_secret_path, password_field=EXCLUDED.password_field,
                    password_env=EXCLUDED.password_env, ca_certificate_path=EXCLUDED.ca_certificate_path,
                    default_topic=EXCLUDED.default_topic, client_id_prefix=EXCLUDED.client_id_prefix,
                    session_timeout_ms=EXCLUDED.session_timeout_ms,
                    custom_properties=EXCLUDED.custom_properties,
                    updated_at=CURRENT_TIMESTAMP
                """, profile.id(), profile.name(), profile.bootstrapServers(), profile.securityProtocol(), profile.saslMechanism(),
                profile.username(), profile.credentialsSource(), profile.vaultSecretPath(), profile.passwordField(), profile.passwordEnv(),
                profile.caCertificatePath(), profile.defaultTopic(), profile.clientIdPrefix(), profile.sessionTimeoutMs(),
                writeCustomProperties(profile.customProperties()));
        return get(profile.id());
    }

    public String profilePassword(UUID id) {
        String encrypted = jdbc.query("SELECT password_encrypted FROM kafka_profile WHERE id=?",
                rs -> rs.next() ? rs.getString(1) : null, id);
        if (encrypted == null || encrypted.isBlank()) throw new IllegalStateException("Kafka profile password is not configured");
        return secretCodec.decrypt(encrypted);
    }

    @Transactional
    public Profile makeDefault(UUID id) { get(id); clearDefault(); jdbc.update("UPDATE kafka_profile SET is_default=TRUE, updated_at=CURRENT_TIMESTAMP WHERE id=?", id); return get(id); }
    public void delete(UUID id) { Profile profile=get(id); if(profile.defaultProfile()) throw new IllegalArgumentException("Default Kafka profile cannot be deleted"); jdbc.update("DELETE FROM kafka_profile WHERE id=?", id); }
    private void clearDefault() { jdbc.update("UPDATE kafka_profile SET is_default=FALSE, updated_at=CURRENT_TIMESTAMP WHERE is_default=TRUE"); }

    private Profile map(ResultSet rs, int row) throws SQLException {
        return new Profile(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("bootstrap_servers"), rs.getString("security_protocol"),
                rs.getString("sasl_mechanism"), rs.getString("username"), rs.getString("credentials_source"), rs.getString("vault_secret_path"),
                rs.getString("password_field"), rs.getString("password_env"), rs.getBoolean("password_configured"), rs.getString("ca_certificate_path"), rs.getString("default_topic"),
                rs.getString("client_id_prefix"), rs.getInt("session_timeout_ms"), readCustomProperties(rs.getString("custom_properties")),
                rs.getBoolean("is_default"), instant(rs,"created_at"), instant(rs,"updated_at"));
    }

    private String encryptedPasswordForCreate(ProfileRequest request) {
        if (!isPlainSource(normalizedSource(request.credentialsSource()))) return null;
        if (request.password() == null || request.password().isBlank()) throw new IllegalArgumentException("Kafka password is required for PLAIN credentials source");
        if (!secretCodec.available()) throw new IllegalStateException("S3_PERF_BOOTSTRAP_KEY must be set before saving Kafka passwords");
        return secretCodec.encrypt(request.password());
    }

    private String encryptedPasswordForUpdate(UUID id, String source, String newPassword) {
        if (!isPlainSource(source)) return null;
        if (newPassword != null && !newPassword.isBlank()) {
            if (!secretCodec.available()) throw new IllegalStateException("S3_PERF_BOOTSTRAP_KEY must be set before saving Kafka passwords");
            return secretCodec.encrypt(newPassword);
        }
        String existing = jdbc.query("SELECT password_encrypted FROM kafka_profile WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, id);
        if (existing == null || existing.isBlank()) throw new IllegalArgumentException("Kafka password is required for PLAIN credentials source");
        return existing;
    }

    private static Instant instant(ResultSet rs,String column)throws SQLException{OffsetDateTime value=rs.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static void validate(ProfileRequest r, boolean update, Profile existing){
        if(r==null||r.name()==null||r.name().isBlank())throw new IllegalArgumentException("Profile name is required");
        if(r.bootstrapServers()==null||r.bootstrapServers().isBlank())throw new IllegalArgumentException("Kafka bootstrap servers are required");
        String protocol=normalizedProtocol(r.securityProtocol());
        if(protocol.startsWith("SASL_")&&(r.saslMechanism()==null||r.saslMechanism().isBlank()))throw new IllegalArgumentException("SASL mechanism is required for "+protocol);
        String source=normalizedSource(r.credentialsSource());
        if(protocol.startsWith("SASL_")&&"NONE".equals(source))throw new IllegalArgumentException("SASL profile requires PLAIN, ENVIRONMENT or VAULT credentials source");
        if(protocol.startsWith("SASL_")&&"VAULT".equals(source)&&(r.vaultSecretPath()==null||r.vaultSecretPath().isBlank()))throw new IllegalArgumentException("Vault secret path is required for SASL/VAULT");
        if(protocol.startsWith("SASL_")&&"ENVIRONMENT".equals(source)&&(r.passwordEnv()==null||r.passwordEnv().isBlank()))throw new IllegalArgumentException("Environment variable name is required for SASL/ENVIRONMENT");
        if(protocol.startsWith("SASL_")&&isPlainSource(source)&&!update&&(r.password()==null||r.password().isBlank()))throw new IllegalArgumentException("Kafka password is required for SASL/PLAIN credentials");
        if(protocol.startsWith("SASL_")&&isPlainSource(source)&&update&&(r.password()==null||r.password().isBlank())&&(existing==null||!existing.passwordConfigured()))throw new IllegalArgumentException("Kafka password is required for SASL/PLAIN credentials");
        normalizedSessionTimeout(r.sessionTimeoutMs());
        normalizeCustomProperties(r.customProperties());
    }
    private static String normalizedProtocol(String value){String protocol=defaultValue(value,"PLAINTEXT").toUpperCase();if(!List.of("PLAINTEXT","SSL","SASL_PLAINTEXT","SASL_SSL").contains(protocol))throw new IllegalArgumentException("Unsupported Kafka security protocol: "+protocol);return protocol;}
    private static String normalizedSource(String value){String source=defaultValue(value,"NONE").toUpperCase();if("PROFILE".equals(source))return "PLAIN";if(!List.of("VAULT","ENVIRONMENT","PLAIN","NONE").contains(source))throw new IllegalArgumentException("Unsupported Kafka credentials source: "+source);return source;}
    private static boolean isPlainSource(String source){return "PLAIN".equals(source)||"PROFILE".equals(source);}
    private static String defaultValue(String value,String fallback){return value==null||value.isBlank()?fallback:value.trim();}
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
    private static int normalizedSessionTimeout(Integer value){
        int timeout=value==null?10000:value;
        if(timeout<1000||timeout>300000)throw new IllegalArgumentException("session.timeout.ms must be between 1000 and 300000 ms");
        return timeout;
    }

    private static Map<String,String> normalizeCustomProperties(Map<String,String> values){
        if(values==null||values.isEmpty())return Map.of();
        Map<String,String> normalized=new LinkedHashMap<>();
        values.forEach((rawKey,rawValue)->{
            String key=rawKey==null?"":rawKey.trim();
            String value=rawValue==null?"":rawValue.trim();
            if(key.isBlank())throw new IllegalArgumentException("Kafka property name must not be blank");
            if(key.length()>255)throw new IllegalArgumentException("Kafka property name is too long: "+key);
            if(MANAGED_PROPERTIES.contains(key))throw new IllegalArgumentException("Kafka property is managed by a dedicated profile field: "+key);
            if(value.length()>8192)throw new IllegalArgumentException("Kafka property value is too long: "+key);
            normalized.put(key,value);
        });
        return Map.copyOf(normalized);
    }

    private String writeCustomProperties(Map<String,String> values){
        try{return objectMapper.writeValueAsString(normalizeCustomProperties(values));}
        catch(Exception e){throw new IllegalArgumentException("Cannot serialize Kafka custom properties",e);}
    }

    private Map<String,String> readCustomProperties(String json){
        if(json==null||json.isBlank())return Map.of();
        try{return normalizeCustomProperties(objectMapper.readValue(json,new TypeReference<LinkedHashMap<String,String>>(){}));}
        catch(Exception e){throw new IllegalStateException("Cannot read Kafka custom properties",e);}
    }

    public record ProfileRequest(String name,String bootstrapServers,String securityProtocol,String saslMechanism,String username,String credentialsSource,String vaultSecretPath,String passwordField,String passwordEnv,String password,String caCertificatePath,String defaultTopic,String clientIdPrefix,Integer sessionTimeoutMs,Map<String,String> customProperties,boolean defaultProfile){
        /** Compatibility constructor for M1-M4 code written before inline password credentials were added. */
        public ProfileRequest(String name,String bootstrapServers,String securityProtocol,String saslMechanism,String username,String credentialsSource,String vaultSecretPath,String passwordField,String passwordEnv,String caCertificatePath,String defaultTopic,String clientIdPrefix,boolean defaultProfile) {
            this(name, bootstrapServers, securityProtocol, saslMechanism, username, credentialsSource, vaultSecretPath,
                    passwordField, passwordEnv, null, caCertificatePath, defaultTopic, clientIdPrefix, 10000, Map.of(), defaultProfile);
        }
    }

    public record Profile(UUID id,String name,String bootstrapServers,String securityProtocol,String saslMechanism,String username,String credentialsSource,String vaultSecretPath,String passwordField,String passwordEnv,boolean passwordConfigured,String caCertificatePath,String defaultTopic,String clientIdPrefix,int sessionTimeoutMs,Map<String,String> customProperties,boolean defaultProfile,Instant createdAt,Instant updatedAt){
        /** Compatibility constructor for code written before session.timeout.ms was added. */
        public Profile(UUID id,String name,String bootstrapServers,String securityProtocol,String saslMechanism,String username,String credentialsSource,String vaultSecretPath,String passwordField,String passwordEnv,boolean passwordConfigured,String caCertificatePath,String defaultTopic,String clientIdPrefix,boolean defaultProfile,Instant createdAt,Instant updatedAt) {
            this(id, name, bootstrapServers, securityProtocol, saslMechanism, username, credentialsSource, vaultSecretPath,
                    passwordField, passwordEnv, passwordConfigured, caCertificatePath, defaultTopic, clientIdPrefix, 10000, Map.of(), defaultProfile, createdAt, updatedAt);
        }
        /** Compatibility constructor for M1-M4 code written before passwordConfigured was added. */
        public Profile(UUID id,String name,String bootstrapServers,String securityProtocol,String saslMechanism,String username,String credentialsSource,String vaultSecretPath,String passwordField,String passwordEnv,String caCertificatePath,String defaultTopic,String clientIdPrefix,boolean defaultProfile,Instant createdAt,Instant updatedAt) {
            this(id, name, bootstrapServers, securityProtocol, saslMechanism, username, credentialsSource, vaultSecretPath,
                    passwordField, passwordEnv, false, caCertificatePath, defaultTopic, clientIdPrefix, 10000, Map.of(), defaultProfile, createdAt, updatedAt);
        }
    }
}
