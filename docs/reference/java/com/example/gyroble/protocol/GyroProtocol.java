package com.example.gyroble.protocol;

/**
 * 陀螺仪 BLE 自定义协议（编解码层，纯 Java，无 Android 依赖，可写单元测试）。
 *
 * 帧格式：
 *   ┌───────┬───────┬──────┬──────┬──────────────┬──────┐
 *   │ 0xAA  │ 0x55  │ LEN  │ CMD  │ DATA[LEN]    │ CHK  │
 *   │ 帧头1 │ 帧头2 │ 数据 │ 命令 │ 数据域       │ 校验 │
 *   └───────┴───────┴──────┴──────┴──────────────┴──────┘
 *   LEN  = DATA 的字节数（不含 CMD）
 *   CHK  = (LEN + CMD + DATA[0] + ... + DATA[LEN-1]) & 0xFF（模 256 累加和）
 *
 * 字节序：多字节字段一律大端（高字节在前）。
 * 完整命令定义见《BLE提取与陀螺仪自定义协议指导文档》第 3 章。
 */
public final class GyroProtocol {

    private GyroProtocol() {
    }

    /* ------------------------------ 帧头 ------------------------------ */
    public static final byte FRAME_HEAD1 = (byte) 0xAA;
    public static final byte FRAME_HEAD2 = (byte) 0x55;
    /** 数据域最大长度保护（防噪声导致解析器分配超大内存） */
    public static final int MAX_DATA_LEN = 64;

    /* ------------------------------ 命令 ------------------------------ */
    /** 0x01 设置纬度        主机→设备，DATA: int32 大端，单位 1e-6 度 */
    public static final byte CMD_SET_LATITUDE = (byte) 0x01;
    /** 0x02 寻北            主机→设备，无 DATA；完成后设备用 0x06 上报结果 */
    public static final byte CMD_START_NORTH_SEEK = (byte) 0x02;
    /** 0x03 进入导航        主机→设备，无 DATA；之后设备连续推送 0x04 */
    public static final byte CMD_ENTER_NAVIGATION = (byte) 0x03;
    /** 0x04 导航数据        设备→主机（通知），连续推送 */
    public static final byte CMD_NAV_DATA = (byte) 0x04;
    /** 0x05 退出导航        主机→设备，无 DATA；设备停止推送 0x04 */
    public static final byte CMD_EXIT_NAVIGATION = (byte) 0x05;
    /** 0x06 寻北结果上报    设备→主机，DATA: result(1B) + 方位角 uint16(2B, 0.01°) */
    public static final byte CMD_NORTH_SEEK_RESULT = (byte) 0x06;
    /** 0x7F 通用应答        设备→主机，DATA: 被应答CMD(1B) + 结果码(1B) */
    public static final byte CMD_ACK = (byte) 0x7F;

    /* ------------------------------ 结果码 ------------------------------ */
    public static final int RESULT_OK = 0x00;              // 成功
    public static final int RESULT_NO_LATITUDE = 0x01;     // 未设置纬度
    public static final int RESULT_STATE_ERROR = 0x02;     // 状态错误（重复启动等）
    public static final int RESULT_BAD_PARAM = 0x03;       // 参数超范围
    public static final int RESULT_HW_FAULT = 0x04;        // 传感器/硬件故障
    public static final int RESULT_OTHER = 0xFF;           // 其他错误

    /** 寻北结果帧(0x06)中的 result 取值 */
    public static final int SEEK_OK = 0x00;                // 寻北成功，方位角有效
    public static final int SEEK_TIMEOUT = 0x01;           // 寻北超时
    public static final int SEEK_ABORTED = 0x02;           // 寻北被中断
    public static final int SEEK_HW_FAULT = 0x04;          // 寻北期间硬件故障

    /* ------------------------------ 状态字位定义（导航数据帧 bit 位） ------------------------------ */
    public static final int NAV_BIT_SEEK_DONE = 0x01;      // bit0 寻北完成
    public static final int NAV_BIT_NAV_VALID = 0x02;      // bit1 导航输出有效
    public static final int NAV_BIT_SELF_TEST = 0x04;      // bit2 陀螺自检通过
    public static final int NAV_BIT_LAT_VALID = 0x08;      // bit3 纬度已设置

    /* ============================== 组帧 ============================== */

    /** 计算校验和：LEN + CMD + DATA 全部字节的模 256 累加和 */
    public static byte checksum(int len, byte cmd, byte[] data) {
        int sum = len + (cmd & 0xFF);
        if (data != null) {
            for (byte b : data) {
                sum += b & 0xFF;
            }
        }
        return (byte) (sum & 0xFF);
    }

    /** 组装完整帧：AA 55 | LEN | CMD | DATA | CHK */
    public static byte[] buildFrame(byte cmd, byte[] data) {
        if (data == null) {
            data = new byte[0];
        }
        if (data.length > MAX_DATA_LEN) {
            throw new IllegalArgumentException("data too long: " + data.length);
        }
        byte[] frame = new byte[data.length + 5];
        frame[0] = FRAME_HEAD1;
        frame[1] = FRAME_HEAD2;
        frame[2] = (byte) data.length;
        frame[3] = cmd;
        System.arraycopy(data, 0, frame, 4, data.length);
        frame[frame.length - 1] = checksum(data.length, cmd, data);
        return frame;
    }

    /** 0x01 设置纬度。latitudeDeg ∈ [-90, 90]，编码为 int32 大端，单位 1e-6 度 */
    public static byte[] buildSetLatitude(double latitudeDeg) {
        if (latitudeDeg < -90.0 || latitudeDeg > 90.0) {
            throw new IllegalArgumentException("latitude out of range: " + latitudeDeg);
        }
        int v = (int) Math.round(latitudeDeg * 1_000_000.0);
        byte[] data = new byte[]{
                (byte) ((v >> 24) & 0xFF),
                (byte) ((v >> 16) & 0xFF),
                (byte) ((v >> 8) & 0xFF),
                (byte) (v & 0xFF)};
        return buildFrame(CMD_SET_LATITUDE, data);
    }

    /** 0x02 寻北（无参数） */
    public static byte[] buildStartNorthSeek() {
        return buildFrame(CMD_START_NORTH_SEEK, null);
    }

    /** 0x03 进入导航（无参数） */
    public static byte[] buildEnterNavigation() {
        return buildFrame(CMD_ENTER_NAVIGATION, null);
    }

    /** 0x05 退出导航（无参数） */
    public static byte[] buildExitNavigation() {
        return buildFrame(CMD_EXIT_NAVIGATION, null);
    }

    /* ============================== 解帧 ============================== */

    /** 导航数据帧(0x04)解析结果 */
    public static final class NavData {
        /** 航向角 0 ~ 359.99 度 */
        public double headingDeg;
        /** 俯仰角 ±90 度 */
        public double pitchDeg;
        /** 横滚角 ±180 度 */
        public double rollDeg;
        /** 状态字，见 NAV_BIT_* */
        public int status;
        /** 帧序号 0~255 滚动，可用于检测丢帧 */
        public int sequence;

        public boolean isNavValid() {
            return (status & NAV_BIT_NAV_VALID) != 0;
        }

        @Override
        public String toString() {
            return String.format("heading=%.2f pitch=%.2f roll=%.2f status=0x%02X seq=%d",
                    headingDeg, pitchDeg, rollDeg, status, sequence);
        }
    }

    /** 解析导航数据帧的 DATA 域（8 字节），失败返回 null */
    public static NavData parseNavData(byte[] data) {
        if (data == null || data.length < 8) {
            return null;
        }
        NavData nav = new NavData();
        nav.headingDeg = (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) / 100.0;
        nav.pitchDeg = (short) (((data[2] & 0xFF) << 8) | (data[3] & 0xFF)) / 100.0;
        nav.rollDeg = (short) (((data[4] & 0xFF) << 8) | (data[5] & 0xFF)) / 100.0;
        nav.status = data[6] & 0xFF;
        nav.sequence = data[7] & 0xFF;
        return nav;
    }

    /** 解析通用应答帧(0x7F)的 DATA 域，返回 int[]{被应答CMD, 结果码}，失败返回 null */
    public static int[] parseAck(byte[] data) {
        if (data == null || data.length < 2) {
            return null;
        }
        return new int[]{data[0] & 0xFF, data[1] & 0xFF};
    }

    /** 解析寻北结果帧(0x06)的 DATA 域，返回 int[]{结果码, 方位角x100}，失败返回 null */
    public static int[] parseNorthSeekResult(byte[] data) {
        if (data == null || data.length < 3) {
            return null;
        }
        int heading = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        return new int[]{data[0] & 0xFF, heading};
    }

    /* ============================== 流式解析器 ============================== */

    /**
     * 流式帧解析器（状态机）。
     *
     * BLE 通知以“包”为单位到达：一个通知可能只有半帧，也可能粘连多帧，
     * 因此必须用状态机逐字节累积、断帧续接。每次收到通知就把 byte[] 喂给
     * feed()，解析出完整帧即回调 onFrame。
     *
     * 注意：本类非线程安全，请在单一接收线程中使用。
     */
    public static final class Parser {

        public interface FrameListener {
            /** 解析出一帧完整且校验正确的数据 */
            void onFrame(byte cmd, byte[] data);
        }

        private static final int S_HEAD1 = 0;
        private static final int S_HEAD2 = 1;
        private static final int S_LEN = 2;
        private static final int S_CMD = 3;
        private static final int S_DATA = 4;
        private static final int S_CHK = 5;

        private int state = S_HEAD1;
        private int len;
        private byte cmd;
        private int idx;
        private byte[] data;

        /** 复位解析器（如断线重连后调用，丢弃半帧状态） */
        public void reset() {
            state = S_HEAD1;
            len = 0;
            idx = 0;
            data = null;
        }

        public void feed(byte[] buf, FrameListener listener) {
            if (buf == null) {
                return;
            }
            feed(buf, buf.length, listener);
        }

        public void feed(byte[] buf, int length, FrameListener listener) {
            for (int i = 0; i < length; i++) {
                byte b = buf[i];
                switch (state) {
                    case S_HEAD1:
                        if (b == FRAME_HEAD1) {
                            state = S_HEAD2;
                        }
                        break;
                    case S_HEAD2:
                        if (b == FRAME_HEAD2) {
                            state = S_LEN;
                        } else if (b != FRAME_HEAD1) {
                            state = S_HEAD1; // 失步，重新找帧头
                        }
                        // b == FRAME_HEAD1：保持 S_HEAD2，继续等 0x55
                        break;
                    case S_LEN:
                        len = b & 0xFF;
                        if (len > MAX_DATA_LEN) {
                            state = S_HEAD1; // 长度非法，视为噪声
                        } else {
                            state = S_CMD;
                        }
                        break;
                    case S_CMD:
                        cmd = b;
                        idx = 0;
                        if (len == 0) {
                            data = new byte[0];
                            state = S_CHK;
                        } else {
                            data = new byte[len];
                            state = S_DATA;
                        }
                        break;
                    case S_DATA:
                        data[idx++] = b;
                        if (idx == len) {
                            state = S_CHK;
                        }
                        break;
                    case S_CHK:
                        if (b == checksum(len, cmd, data) && listener != null) {
                            listener.onFrame(cmd, data);
                        }
                        state = S_HEAD1;
                        break;
                    default:
                        state = S_HEAD1;
                        break;
                }
            }
        }
    }

    /* ============================== 调试工具 ============================== */

    /** byte[] → 十六进制字符串（空格分隔，大写），日志调试用 */
    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}
