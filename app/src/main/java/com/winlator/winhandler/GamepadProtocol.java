package com.winlator.winhandler;

final class GamepadProtocol {
    static final byte DINPUT_MAPPER_TYPE_STANDARD = 0;
    static final byte DINPUT_MAPPER_TYPE_XINPUT = 1;

    static final byte FLAG_DINPUT_MAPPER_STANDARD = 0x01;
    static final byte FLAG_DINPUT_MAPPER_XINPUT = 0x02;
    static final byte FLAG_INPUT_TYPE_XINPUT = 0x04;
    static final byte FLAG_INPUT_TYPE_DINPUT = 0x08;

    private GamepadProtocol() {
    }

    static byte encodeGamepadType(boolean isXInput, byte dinputMapperType) {
        final byte mapperFlag;

        switch (dinputMapperType) {
            case DINPUT_MAPPER_TYPE_STANDARD:
                mapperFlag = FLAG_DINPUT_MAPPER_STANDARD;
                break;
            case DINPUT_MAPPER_TYPE_XINPUT:
                mapperFlag = FLAG_DINPUT_MAPPER_XINPUT;
                break;
            default:
                throw new IllegalArgumentException("Unsupported DirectInput mapper type: " + dinputMapperType);
        }

        return (byte)(mapperFlag | (isXInput ? FLAG_INPUT_TYPE_XINPUT : FLAG_INPUT_TYPE_DINPUT));
    }
}
