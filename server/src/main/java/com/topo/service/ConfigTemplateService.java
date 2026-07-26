package com.topo.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 配置命令模板库
 *
 * 每台设备能配什么、命令怎么写，都是人定好的。
 * AI 只负责：1. 意图识别（用户想配什么） 2. 填变量（用拓扑里的真实接口名和IP）
 */
@Service
public class ConfigTemplateService {

    private final CommandKnowledgeService knowledgeService;

    public ConfigTemplateService(CommandKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 型号 → 功能列表
     */
    private static final Map<String, Set<String>> DEVICE_CAPABILITY = Map.of(
        "USG6000V", Set.of("interface","route","gre","ipsec","gre-over-ipsec","vrrp","hrp","nat-server","easy-ip","acl","security-policy","ospf","eth-trunk","telnet"),
        "USG6000V2", Set.of("interface","route","gre","ipsec","gre-over-ipsec","vrrp","hrp","nat-server","easy-ip","acl","security-policy","ospf","eth-trunk","telnet"),
        "S5700", Set.of("interface","route","vlan","stp","eth-trunk","acl","ospf","rip","dhcp","telnet"),
        "S3700", Set.of("interface","route","vlan","stp","eth-trunk","acl","ospf","rip","dhcp","telnet"),
        "AR2220", Set.of("interface","route","gre","ipsec","nat-server","easy-ip","acl","ospf","rip","dhcp","telnet","bgp"),
        "AR1220", Set.of("interface","route","gre","ipsec","nat-server","easy-ip","acl","ospf","rip","dhcp","telnet")
    );

    /**
     * 功能名 → 命令模板。{xxx} 是 AI 要填的变量，[xxx] 可选
     */
    private static final Map<String, String> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("interface", """
            接口配置:
            interface {iface}
             ip address {ip} {mask}
             description {desc}

            变量说明: {iface}=拓扑中该设备的真实接口名，{ip}=IP地址，{mask}=子网掩码如255.255.255.0""");

        TEMPLATES.put("gre", """
            GRE Tunnel配置:
            interface Tunnel{n}
             ip address {tunnel_ip} {tunnel_mask}
             tunnel-protocol gre
             source {source_iface}
             destination {dest_ip}
            [ip route-static {target_net} {target_mask} Tunnel{n}]

            变量说明: {n}=Tunnel编号（选未使用的），{tunnel_ip}=隧道IP（私网地址），{source_iface}=本端公网接口，{dest_ip}=对端公网IP""");

        TEMPLATES.put("vrrp", """
            VRRP双机热备配置:
            主设备:
            interface {iface}
             vrrp vrid 1 virtual-ip {vip} {mask}
             vrrp vrid 1 priority 120

            备设备:
            interface {iface}
             vrrp vrid 1 virtual-ip {vip} {mask}

            变量说明: {iface}=连接到内网的接口，{vip}=虚拟网关IP""");

        TEMPLATES.put("vlan", """
            VLAN配置:
            vlan {vlan_id}
             description {desc}
            interface {iface}
             port link-type access
             port default vlan {vlan_id}

            Trunk口:
            interface {iface}
             port link-type trunk
             port trunk allow-pass vlan {vlan_list}

            变量说明: {vlan_id}=VLAN编号2-4094，{iface}=拓扑中真实接口名""");

        TEMPLATES.put("nat-server", """
            NAT Server配置:
            nat server {name} protocol tcp global {public_ip} {public_port} inside {private_ip} {private_port}

            变量说明: {name}=规则名，{public_ip}=公网IP，{private_ip}=内网服务器IP""");

        TEMPLATES.put("route", """
            静态路由配置:
            ip route-static {dest_net} {dest_mask} {next_hop}
            或
            ip route-static {dest_net} {dest_mask} {out_iface}

            变量说明: {next_hop}=下一跳IP，{out_iface}=出接口名""");

        TEMPLATES.put("acl", """
            ACL配置:
            acl number {num}
             rule {rule_id} permit ip source {src_net} {src_wildcard} destination {dst_net} {dst_wildcard}

            变量说明: {num}=3000-3999基本ACL，{src_wildcard}=反掩码如0.0.0.255""");

        TEMPLATES.put("hrp", """
            HRP双机热备配置:
            hrp enable
            hrp interface Eth-Trunk 1 remote {peer_ip}
            interface Eth-Trunk 1
             ip address {local_ip} {mask}

            变量说明: {peer_ip}=对端防火墙心跳口IP，{local_ip}=本端心跳口IP""");
    }

    /**
     * 查某个型号是否支持某项功能（委托给结构化知识库）
     */
    public boolean supports(String model, String feature) {
        return knowledgeService.supports(model, feature);
    }

    /**
     * 查型号不支持的功能列表
     */
    public Set<String> unsupported(String model, List<String> features) {
        CommandKnowledgeService.ModelKnowledge mk = knowledgeService.getModel(model);
        if (mk == null) return Set.of();
        Set<String> unsupported = new LinkedHashSet<>();
        for (String f : features) {
            if (!mk.capabilities.contains(f.toLowerCase())) unsupported.add(f);
        }
        return unsupported;
    }

    /**
     * 获取功能对应的命令模板
     */
    public String getTemplate(String feature) {
        return TEMPLATES.getOrDefault(feature.toLowerCase(), null);
    }

    /**
     * 查询型号支持的能力列表（文本格式，委托给结构化知识库）
     */
    public String getCapabilities(String model) {
        String text = knowledgeService.getCapabilityText(model);
        if (text != null) return text;
        Set<String> caps = DEVICE_CAPABILITY.get(model);
        if (caps == null) return null;
        return String.join(", ", caps);
    }

    /**
     * 生成模板注入的 Prompt 片段
     */
    public String buildTemplatePrompt(String model, List<String> neededFeatures) {
        StringBuilder sb = new StringBuilder();
        sb.append("设备型号: ").append(model).append("\n");
        sb.append("可配功能: ").append(String.join(", ", DEVICE_CAPABILITY.getOrDefault(model, Set.of()))).append("\n\n");

        // 不支持的功能
        Set<String> unsupported = unsupported(model, neededFeatures);
        if (!unsupported.isEmpty()) {
            sb.append("该设备不支持以下功能，跳过: ").append(String.join(", ", unsupported)).append("\n\n");
        }

        // 只输出该设备支持的模板
        for (String feature : neededFeatures) {
            if (supports(model, feature)) {
                String tmpl = getTemplate(feature);
                if (tmpl != null) {
                    sb.append("=== ").append(feature.toUpperCase()).append(" ===\n");
                    sb.append(tmpl).append("\n\n");
                }
            }
        }

        sb.append("要求: 1. 严格按照以上模板格式输出配置 2. 用拓扑中的真实接口名和IP 3. 不知道的值用<待填写>标注\n");
        return sb.toString();
    }
}
