package com.hezhangjian.ontology.core.connections;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public final class ConnectionPolicy {
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|secret|token|private.?key|access.?key|certificate|credential).*");
    private static final Set<Integer> DENIED_PORTS = Set.of(22, 2375, 2376, 5432 + 10000);
    private final ConnectionProperties properties;

    public ConnectionPolicy(ConnectionProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> validate(ConnectionModels.DataSourceType type, Map<String, Object> raw) {
        if (raw == null) {
            throw new ConnectionProblem("CONFIG_REQUIRED", "连接配置不能为空");
        }
        rejectSecrets(raw);
        Map<String, Object> config = Map.copyOf(raw);
        int timeout = integer(config, "timeoutSeconds", 15);
        if (timeout < 2 || timeout > 60) {
            throw new ConnectionProblem("TIMEOUT_INVALID", "连接超时必须在 2 到 60 秒之间");
        }
        switch (type) {
            case S3_CSV -> validateUri(string(config, "endpoint"), Set.of("http", "https"));
            case MYSQL, POSTGRESQL -> validateHost(string(config, "host"), integer(config, "port", type.name().equals("MYSQL") ? 3306 : 5432));
            case KAFKA -> {
                String servers = string(config, "bootstrapServers");
                for (String server : servers.split(",")) {
                    String[] parts = server.trim().split(":");
                    if (parts.length != 2) throw new ConnectionProblem("TARGET_INVALID", "Bootstrap Server 必须使用 host:port");
                    validateHost(parts[0], Integer.parseInt(parts[1]));
                }
            }
            case EXTERNAL_PULSAR -> {
                String serviceUrl = string(config, "serviceUrl");
                if (serviceUrl.contains("platform/")) {
                    throw new ConnectionProblem("PLATFORM_BUS_FORBIDDEN", "外部 Pulsar 不能指向平台内部事件总线");
                }
                validateUri(serviceUrl, Set.of("pulsar", "pulsar+ssl"));
                validateUri(string(config, "adminUrl"), Set.of("http", "https"));
                String tenant = string(config, "tenant");
                if (tenant.equals("platform")) {
                    throw new ConnectionProblem("PLATFORM_BUS_FORBIDDEN", "platform tenant 由平台管理，不能作为外部连接");
                }
            }
        }
        return config;
    }

    public static String string(Map<String, Object> config, String key) {
        String value = String.valueOf(config.getOrDefault(key, "")).trim();
        if (value.isEmpty()) throw new ConnectionProblem("CONFIG_REQUIRED", "缺少连接参数：" + key);
        return value;
    }

    public static int integer(Map<String, Object> config, String key, int fallback) {
        Object value = config.get(key);
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException cause) { throw new ConnectionProblem("CONFIG_INVALID", key + " 必须是整数"); }
    }

    private void rejectSecrets(Map<?, ?> value) {
        value.forEach((key, item) -> {
            if (SECRET_KEY.matcher(String.valueOf(key)).matches()) {
                throw new ConnectionProblem("SECRET_IN_CONFIG", "敏感凭据必须通过 credential 字段提交");
            }
            if (item instanceof Map<?, ?> nested) rejectSecrets(nested);
            if (item instanceof Collection<?> collection) collection.forEach(entry -> {
                if (entry instanceof Map<?, ?> nested) rejectSecrets(nested);
            });
        });
    }

    private void validateUri(String raw, Set<String> allowedSchemes) {
        try {
            URI uri = URI.create(raw);
            if (!allowedSchemes.contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT)) || uri.getHost() == null) {
                throw new ConnectionProblem("TARGET_INVALID", "目标地址协议或主机无效");
            }
            int port = uri.getPort() > 0 ? uri.getPort() : switch (uri.getScheme()) {
                case "https", "pulsar+ssl" -> 443;
                case "pulsar" -> 6650;
                default -> 80;
            };
            validateHost(uri.getHost(), port);
        } catch (IllegalArgumentException cause) {
            throw new ConnectionProblem("TARGET_INVALID", "目标地址格式无效");
        }
    }

    private void validateHost(String host, int port) {
        if (port < 1 || port > 65535 || DENIED_PORTS.contains(port)) {
            throw new ConnectionProblem("PORT_FORBIDDEN", "目标端口不在允许范围内");
        }
        if (properties.allowedPrivateHosts().contains(host)) return;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || isCloudMetadata(address)) {
                    throw new ConnectionProblem("PRIVATE_TARGET_FORBIDDEN", "目标地址不在允许的网络范围内");
                }
            }
        } catch (UnknownHostException cause) {
            throw new ConnectionProblem("DNS_FAILED", "无法解析目标地址");
        }
    }

    private boolean isCloudMetadata(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && Byte.toUnsignedInt(bytes[0]) == 169 && Byte.toUnsignedInt(bytes[1]) == 254;
    }
}
