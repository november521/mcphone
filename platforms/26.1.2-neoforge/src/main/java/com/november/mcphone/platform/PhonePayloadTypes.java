package com.november.mcphone.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 保留消息协议的完整命名空间；新版原版 createType 只接受 minecraft 下的路径。 */
public final class PhonePayloadTypes {
    private PhonePayloadTypes() {}

    public static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> create(String id) {
        return new CustomPacketPayload.Type<>(Identifier.parse(id));
    }
}
