import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class Main {
    private static int SERIAL = 1;
    private static final String HUB_NAME = "HUB";

    private static final int BROADCAST_ADDRESS = 16383;

    public static int compute_CRC8_Simple(byte[] bytes) {
        final int generator = 0x1D;
        int crc = 0;
        for (byte b : bytes) {
            int currByte = b & 0xff;
            crc ^= currByte;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) {
                    crc = ((crc << 1) ^ generator) & 0xff;
                } else {
                    crc <<= 1;
                }
            }
        }
        return crc;
    }

    public static int[] decodeULEB128(int indexFrom, byte[] array) {
        int[] out = new int[2];
        int result = 0;
        int shift = 0;
        int index = indexFrom;
        while (true) {
            result |= (array[index] & (byte) 127) << shift * 7;
            shift++;
            index++;
            if (array[index - 1] >= 0)
                break;
        }
        out[0] = result;
        out[1] = shift;
        return out;
    }

    public static ArrayList<Byte> encodeULEB128(int value) {
        ArrayList<Byte> list = new ArrayList<>();
        do {
            byte b = (byte) (value & 0x7f);
            value >>= 7;
            if (value != 0) {
                b |= 0x80;
            }
            list.add(b);
        } while (value != 0);
        return list;
    }

    public static long[] decodeTimeFromULEB128(int indexFrom, byte[] array) {
        long[] out = new long[2];
        long result = 0L;
        int shift = 0;
        int index = indexFrom;
        while (true) {
            result |= (long) (array[index] & 127) << shift * 7;
            shift++;
            index++;
            if (array[index - 1] > 0) // high-order bit of byte == 0;
                break;
        }
        out[0] = result;
        out[1] = shift;
        return out;
    }

    // Функция декодирует пакеты данных из URL-encoded Base64 строки в ArrayList содержащий информативную часть пакета (Payload).
    public static ArrayList<Payload> decodePackage(String response) {

        Base64.Decoder decoder = Base64.getUrlDecoder();
        byte[] decodedArray = decoder.decode(response);
        ArrayList<Payload> payloads = new ArrayList<>();

        int curIndex = 0;
        while (curIndex < decodedArray.length) {
            int payloadLength = decodedArray[curIndex++] & 0xff;
            int crc8;

            try {
                crc8 = decodedArray[curIndex + payloadLength] & 0xff;
            } catch (ArrayIndexOutOfBoundsException e) {
                // Дальнейшее корректное чтение и декодирование пакетов невозможно
                return payloads;
            }

            // Для того чтобы перейти к следующему пакету при появлении ошибки
            int nextPackageStartIndex = curIndex + payloadLength + 1;

            if (crc8 != compute_CRC8_Simple(Arrays.copyOfRange(decodedArray, curIndex, curIndex + payloadLength))) {
                curIndex += nextPackageStartIndex;
                continue;
            }

            int[] payloadParam = new int[5];
            int counter = 0;

            while (counter < 5) {
                int[] numberAndCountBytes = decodeULEB128(curIndex, decodedArray);
                payloadParam[counter] = numberAndCountBytes[0];
                curIndex += numberAndCountBytes[1];
                counter++;
            }

            Payload payload = new Payload(payloadParam[0], payloadParam[1], payloadParam[2], payloadParam[3], payloadParam[4]);

            switch (payload.cmd) {
                // 0x01 WHOISHERE and 0x02 IAMHERE
                case 1, 2 -> {
                    int strLength = decodedArray[curIndex++] & 0xff;
                    StringBuilder name = new StringBuilder();
                    for (int i = 0; i < strLength; i++) name.append((char) (decodedArray[curIndex++] & 0xff));

                    switch (payload.devType) {
                        //0x01 - SmartHub
                        case 1 -> payload.setCmdBody(new DeviceBody(name.toString(), null));

                        //0x02 - EnvSensor
                        case 2 -> {
                            boolean[] sensors = new boolean[4];
                            int temp = decodedArray[curIndex++] & 0xff;
                            if ((temp & 1) == 1) sensors[0] = true;
                            if ((temp & 2) == 2) sensors[1] = true;
                            if ((temp & 4) == 4) sensors[2] = true;
                            if ((temp & 8) == 8) sensors[3] = true;

                            int triggersLength = decodedArray[curIndex++] & 0xff;
                            EnvSensorProps.Trigger[] triggers = new EnvSensorProps.Trigger[triggersLength];
                            for (int i = 0; i < triggersLength; i++) {
                                int op = decodedArray[curIndex++] & 0xff;

                                int[] numberAndCountBytes = decodeULEB128(curIndex, decodedArray);
                                curIndex += numberAndCountBytes[1];

                                StringBuilder deviceName = new StringBuilder();
                                int nameLength = decodedArray[curIndex++] & 0xff;
                                for (int j = 0; j < nameLength; j++)
                                    deviceName.append((char) (decodedArray[curIndex++] & 0xff));

                                triggers[i] = new EnvSensorProps.Trigger((byte) (op & 1), (op & 2) == 2, (op & 3), numberAndCountBytes[0], deviceName.toString());
                            }
                            payload.setCmdBody(new DeviceBody(name.toString(), new EnvSensorProps(sensors, triggers)));
                        }
                        // 0x03 Switch
                        case 3 -> {
                            int stringsLength = decodedArray[curIndex++] & 0xff;
                            String[] strings = new String[stringsLength];
                            for (int i = 0; i < stringsLength; i++) {
                                int nameLength = decodedArray[curIndex++] & 0xff;

                                StringBuilder deviceName = new StringBuilder();
                                for (int j = 0; j < nameLength; j++)
                                    deviceName.append((char) (decodedArray[curIndex++] & 0xff));

                                strings[i] = deviceName.toString();
                            }
                            payload.setCmdBody(new DeviceBody(name.toString(), new SwitchProps(strings)));
                        }
                        // 0x04 Lamp, 0x05 Socket, 0x06 Timer
                        case 4, 5, 6 -> {
                            payload.setCmdBody(new DeviceBody(name.toString(), null));
                        }
                        default -> {

                        }
                    }
                }

                // STATUS
                case 4 -> {
                    switch (payload.devType) {
                        // 0x02 - EnvSensor
                        case 2 -> {
                            int valuesLength = decodedArray[curIndex++] & 0xff;
                            double[] values = new double[valuesLength];
                            for (int i = 0; i < valuesLength; i++) {
                                int[] numberAndCountBytes = decodeULEB128(curIndex, decodedArray);
                                values[i] = numberAndCountBytes[0];
                                curIndex += numberAndCountBytes[1];
                            }
                            payload.setCmdBody(new EnvSensorStatus(values));
                        }
                        // 0x03 - Switch, 0x04 - Lamp, 0x05 - Socket
                        case 3, 4, 5 -> {
                            int turnOn = decodedArray[curIndex++] & 0xff;
                            payload.setCmdBody(new StatusOrSetStatusBody(turnOn == 1));
                        }
                    }
                }

                // 0x03 - GETSTATUS, 0x05 - SETSTATUS
                case 3, 5 -> {
                    curIndex = nextPackageStartIndex;
                    continue;
                }

                // 0x06 - TICK
                case 6 -> {
                    long[] numberAndCountBytes = decodeTimeFromULEB128(curIndex, decodedArray);
                    long time = numberAndCountBytes[0];
                    curIndex += numberAndCountBytes[1];
                    payload.setCmdBody(new TimerCmdBody(time));
                }
            }
            payloads.add(payload);
            curIndex++;
        }
        return payloads;
    }

    // Возвращает последовательность байт, которыми кодируется Payload (за исключением cmdBody).
    public static ArrayList<Byte> createPayloadHeader(int address, int dstAddress, int devType, Command command) {
        ArrayList<Byte> byteList = new ArrayList<>();
        byteList.addAll(encodeULEB128(address));
        byteList.addAll(encodeULEB128(dstAddress));
        byteList.addAll(encodeULEB128(SERIAL++));
        byteList.add((byte) devType);
        byteList.add((byte) command.ordinal());
        return byteList;
    }

    // Вычисляет размер Payload в байтах и записывает в позиции 0. Вычисляет crc8 и записывает в последний байт.
    public static byte[] createPayload(ArrayList<Byte> byteList) {
        byte[] byteArray = new byte[byteList.size() + 2];
        byteArray[0] = (byte) (byteList.size() & 0xff);
        for (int i = 1; i < byteArray.length - 1; i++)
            byteArray[i] = byteList.get(i - 1);
        byteArray[byteArray.length - 1] = (byte) (compute_CRC8_Simple(Arrays.copyOfRange(byteArray, 1, byteArray.length - 1)));
        return byteArray;
    }

    public static String encodePayloadWhoIsHere(int address, int dstAddress, int devType) {

        ArrayList<Byte> byteList = createPayloadHeader(address, dstAddress, devType, Command.WHOISHERE);
        byteList.add((byte) HUB_NAME.length());
        for (int i = 0; i < HUB_NAME.length(); i++)
            byteList.add((byte) HUB_NAME.charAt(i));

        byte[] byteArray = createPayload(byteList);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(byteArray);
    }

    public static String encodePayloadIAmHere(int address) {

        ArrayList<Byte> byteList = createPayloadHeader(address, BROADCAST_ADDRESS, 1, Command.IAMHERE);
        byteList.add((byte) HUB_NAME.length());
        for (int i = 0; i < HUB_NAME.length(); i++)
            byteList.add((byte) HUB_NAME.charAt(i));

        byte[] byteArray = createPayload(byteList);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(byteArray);
    }

    public static String encodePayloadGetStatus(int address, int dstAddress, int devType) {

        ArrayList<Byte> byteList = createPayloadHeader(address, dstAddress, devType, Command.GETSTATUS);
        byte[] byteArray = createPayload(byteList);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(byteArray);
    }

    public static String encodePayloadSetStatus(int address, int dstAddress, int devType, byte value) {
        ArrayList<Byte> byteList = createPayloadHeader(address, dstAddress, devType, Command.SETSTATUS);
        byteList.add(value);
        byte[] byteArray = createPayload(byteList);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(byteArray);
    }

    // Отправляет запрос "request" на url, информация полученная от сервера декодируется и сохраняется в список payloads.
    public static int sendRequest(String request, URL url, ArrayList<Payload> payloads) {
        int responseCode = 0;
        boolean isFirstTime = true;
        long startResponseTime = 0;
        try {
            do {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setDoInput(true);

                OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());

                writer.write(request);
                writer.flush();
                writer.close();

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String response = reader.readLine();
                reader.close();

                if (response != null) {
                    response = response.replace(" ", "");
                    ArrayList<Payload> input = decodePackage(response);

                    if (isFirstTime)
                        payloads.addAll(input);
                    else {
                        payloads.set(0, input.get(0));
                        payloads.addAll(input.subList(1, input.size()));
                    }
                }

                if (isFirstTime) {
                    startResponseTime = ((TimerCmdBody) payloads.get(0).getCmdBody()).getTimestamp();
                    isFirstTime = false;
                }
                request = " ";
                responseCode = connection.getResponseCode();
            } while (responseCode == 200 && ((TimerCmdBody) payloads.get(0).getCmdBody()).getTimestamp() - startResponseTime < 300);
        } catch (IOException e) {
            System.exit(99);
        }
        return responseCode;
    }

    // Проверяет, есть ли пакет с командой 0x03 - STATUS от устройства с адресом address. Все другие пакет копирует в очередь ответов.
    public static boolean isRequestAnswered(int address, ArrayList<Payload> payloads, Queue<Payload> responseQueue) {
        boolean isAnswered = false;
        for (Payload p : payloads) {
            // Устройство ответило 0x04 - STATUS
            if (p.getSrc() == address && p.getCmd() == 4)
                isAnswered = true;
            else
                responseQueue.add(p);
        }
        return isAnswered;
    }

    public static void main(String[] args)  {

        URL url = null;
        try {
            url = new URL(args[0]);
        } catch (IOException e) {
            System.exit(99);
        }

        int hubAddress = Integer.parseInt(args[1], 16);

        // Список для приема декодированных пакетов
        ArrayList<Payload> payloads = new ArrayList<>();

        String request = encodePayloadWhoIsHere(hubAddress, BROADCAST_ADDRESS, 1);
        int responseCode = sendRequest(request, url, payloads);

        // Ключ - адрес устройства, Значение - конфигурация устройства
        Map<Integer, Device> devices = new HashMap<>();
        // Ключ - имя устройства, Значение - адрес устройства
        Map<String, Integer> addresses = new HashMap<>();

        for (int i = 1; i < payloads.size(); i++) {

            // Все пакеты, кроме 0x02 - IAMHERE игнорируются
            if (payloads.get(i).getCmd() != 2) {
                continue;
            }

            DeviceBody body = (DeviceBody) payloads.get(i).getCmdBody();
            int address = payloads.get(i).getSrc();
            int type = payloads.get(i).getDevType();
            String name = body.getName();
            devices.put(address, new Device(name, address, type, body.getProps()));
            addresses.put(name, address);
        }

        payloads.clear();

        // Очередь входящих пакетов
        Queue<Payload> responseQueue = new LinkedList<>();

        // Запрос исходных состояний всех устройств
        for (Map.Entry<Integer, Device> d : devices.entrySet()) {
            request = encodePayloadGetStatus(hubAddress, d.getKey(), d.getValue().getDevType());
            responseCode = sendRequest(request, url, payloads);
            responseQueue.addAll(payloads);
            payloads.clear();
        }

        while (responseCode == 200) {

            if (responseQueue.isEmpty()) {
                request = " ";
                responseCode = sendRequest(request, url, payloads);
                responseQueue.addAll(payloads);
                payloads.clear();
            }

            while (!responseQueue.isEmpty()) {
                Payload currPayload = responseQueue.poll();
                switch (currPayload.getCmd()) {

                    // 0x01 - WHOISHERE
                    case 1 -> {
                        DeviceBody body = (DeviceBody) currPayload.getCmdBody();
                        int address = currPayload.getSrc();
                        int type = currPayload.getDevType();
                        devices.put(address, new Device(body.getName(), address, type, body.getProps()));
                        addresses.put(body.getName(), address);

                        // Ответ IAMHERE
                        String innerRequest = encodePayloadIAmHere(hubAddress);
                        responseCode = sendRequest(innerRequest, url, payloads);
                        responseQueue.addAll(payloads);
                        payloads.clear();

                        // 0x06 - TIMER
                        if (type == 6) {
                            continue;
                        }

                        // Запрос GETSTATUS
                        innerRequest = encodePayloadGetStatus(hubAddress, address, type);
                        responseCode = sendRequest(innerRequest, url, payloads);

                        // Проверка пришел ли ответ от устройства
                        // Устройство удаляется, если оно не ответило за 300мс
                        if (!isRequestAnswered(address, payloads, responseQueue)) {
                            devices.remove(address);
                        }
                        payloads.clear();
                    }

                    // 0x04 - STATUS
                    case 4 -> {
                        String innerRequest;
                        int address = currPayload.getSrc();
                        if (currPayload.getDevType() == 3) { // 0x03 - SWITCH
                            byte value = ((StatusOrSetStatusBody) currPayload.getCmdBody()).isTurnOn() ? (byte) 1 : (byte) 0;
                            Device currSwitch = devices.get(address);
                            if (currSwitch == null)
                                continue;

                            for (String s : ((SwitchProps) currSwitch.getDevProps()).getDevNames()) {
                                Integer devAddress = addresses.get(s);
                                if (devAddress == null)
                                    continue;

                                Device lampOrSocket = devices.get(devAddress);
                                innerRequest = encodePayloadSetStatus(hubAddress, lampOrSocket.getAddress(), lampOrSocket.getDevType(), value);

                                // отправить пакет
                                responseCode = sendRequest(innerRequest, url, payloads);

                                // Проверка пришел ли ответ от устройства
                                // Устройство удаляется, если оно не ответило за 300мс
                                if (!isRequestAnswered(devAddress, payloads, responseQueue)) {
                                    devices.remove(devAddress);
                                }
                                payloads.clear();

                            }

                        } else if (currPayload.getDevType() == 2) {
                            Device envSensor = devices.get(address);
                            if (envSensor == null)
                                continue;

                            double[] values = ((EnvSensorStatus) currPayload.getCmdBody()).getValues();
                            boolean[] sensors = ((EnvSensorProps) envSensor.getDevProps()).getSensors();
                            EnvSensorProps.Trigger[] triggers = ((EnvSensorProps) envSensor.getDevProps()).getTriggers();

                            int counter = 0;
                            for (int i = 0; i < sensors.length; i++) {
                                if (!sensors[i])
                                    continue;

                                for (EnvSensorProps.Trigger trigger : triggers) {
                                    Device device = devices.get(addresses.get(trigger.getDeviceName()));
                                    if (device == null)
                                        continue;
                                    if (trigger.getSensor() == i &&
                                            (!trigger.isMoreOrLess() && values[counter] < trigger.getValue() ||
                                                    trigger.isMoreOrLess() && values[counter] > trigger.getValue())) {

                                        int devAddress = device.getAddress();
                                        // включить или выключить
                                        innerRequest = encodePayloadSetStatus(hubAddress, devAddress, device.getDevType(), trigger.getOnOff());

                                        // отправить пакет
                                        responseCode = sendRequest(innerRequest, url, payloads);

                                        // Проверка пришел ли ответ от устройства
                                        // Устройство удаляется, если оно не ответило за 300мс
                                        if (!isRequestAnswered(devAddress, payloads, responseQueue)) {
                                            devices.remove(devAddress);
                                        }

                                        payloads.clear();
                                    }
                                }
                                counter++;
                            }
                        }
                    }

                    // 0x02 - IAMHERE
                    // 0x03 - GETSTATUS
                    // 0x05 - SETSTATUS
                    // 0x06 - TICK
                    case 2, 3, 5, 6 -> {

                    }
                }
            }
        }

        if (responseCode == 204)
            System.exit(0);
        else System.exit(99);
    }
    enum Command {
        EMPTY,
        WHOISHERE,
        IAMHERE,
        GETSTATUS,
        STATUS,
        SETSTATUS,
        TICK
    }

    static class Payload {
        int src;
        int dst;
        int serial;
        int devType;
        int cmd;
        CmdBody cmdBody;

        public Payload(int src, int dst, int serial, int devType, int cmd) {
            this.src = src;
            this.dst = dst;
            this.serial = serial;
            this.devType = devType;
            this.cmd = cmd;
        }

        @Override
        public String toString() {
            return "Payload{" +
                    "src=" + src +
                    ", dst=" + dst +
                    ", serial=" + serial +
                    ", devType=" + devType +
                    ", cmd=" + cmd +
                    ", cmdBody=" + cmdBody +
                    '}';
        }

        public int getSrc() {
            return src;
        }

        public void setSrc(int src) {
            this.src = src;
        }

        public int getDst() {
            return dst;
        }

        public void setDst(int dst) {
            this.dst = dst;
        }

        public int getSerial() {
            return serial;
        }

        public void setSerial(int serial) {
            this.serial = serial;
        }

        public int getDevType() {
            return devType;
        }

        public void setDevType(int devType) {
            this.devType = devType;
        }

        public int getCmd() {
            return cmd;
        }

        public void setCmd(int cmd) {
            this.cmd = cmd;
        }

        public void setCmdBody(CmdBody cmdBody) {
            this.cmdBody = cmdBody;
        }

        public CmdBody getCmdBody() {
            return cmdBody;
        }
    }

    static abstract class CmdBody {

    }

    static class TimerCmdBody extends CmdBody {
        private long timestamp;

        public TimerCmdBody(long timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return "TimerCmdBody{" +
                    "timestamp=" + timestamp +
                    '}';
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    static class EnvSensorStatus extends CmdBody {
        private double[] values;

        public EnvSensorStatus(double[] values) {
            this.values = values;
        }

        public double[] getValues() {
            return values;
        }

        public void setValues(double[] values) {
            this.values = values;
        }

        @Override
        public String toString() {
            return "EnvSensorStatus{" +
                    "values=" + Arrays.toString(values) +
                    '}';
        }
    }

    static class DeviceBody extends CmdBody {
        private String name;
        private DevProps props;

        public DeviceBody(String name, DevProps props) {
            this.name = name;
            this.props = props;
        }

        @Override
        public String toString() {
            return "DeviceBody{" +
                    "name='" + name + '\'' +
                    ", props=" + props +
                    '}';
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public DevProps getProps() {
            return props;
        }

        public void setProps(DevProps props) {
            this.props = props;
        }
    }

    static class StatusOrSetStatusBody extends CmdBody {
        private boolean turnOn;

        public StatusOrSetStatusBody(boolean turnOn) {
            this.turnOn = turnOn;
        }

        @Override
        public String toString() {
            return "StatusOrSetStatusBody{" +
                    "turnOn=" + turnOn +
                    '}';
        }

        public boolean isTurnOn() {
            return turnOn;
        }

        public void setTurnOn(boolean turnOn) {
            this.turnOn = turnOn;
        }
    }

    static abstract class DevProps {

    }

    static class EnvSensorProps extends DevProps {
        private boolean[] sensors;
        private Trigger[] triggers;

        public boolean[] getSensors() {
            return sensors;
        }

        public void setSensors(boolean[] sensors) {
            this.sensors = sensors;
        }

        public Trigger[] getTriggers() {
            return triggers;
        }

        public void setTriggers(Trigger[] triggers) {
            this.triggers = triggers;
        }

        static class Trigger {
            private byte onOff;
            private boolean moreOrLess;
            private int sensor;
            private int value;
            private String deviceName;

            public Trigger(byte onOff, boolean moreOrLess, int sensor, int value, String deviceName) {
                this.onOff = onOff;
                this.moreOrLess = moreOrLess;
                this.sensor = sensor;
                this.value = value;
                this.deviceName = deviceName;
            }

            @Override
            public String toString() {
                return "Trigger{" +
                        "turnOn=" + onOff +
                        ", moreOrLess=" + moreOrLess +
                        ", sensor=" + sensor +
                        ", value=" + value +
                        ", name='" + deviceName + '\'' +
                        '}';
            }

            public byte getOnOff() {
                return onOff;
            }

            public void setOnOff(byte onOff) {
                this.onOff = onOff;
            }

            public boolean isMoreOrLess() {
                return moreOrLess;
            }

            public void setMoreOrLess(boolean moreOrLess) {
                this.moreOrLess = moreOrLess;
            }

            public int getSensor() {
                return sensor;
            }

            public void setSensor(int sensor) {
                this.sensor = sensor;
            }

            public int getValue() {
                return value;
            }

            public void setValue(int value) {
                this.value = value;
            }

            public String getDeviceName() {
                return deviceName;
            }

            public void setName(String deviceName) {
                this.deviceName = deviceName;
            }
        }

        public EnvSensorProps(boolean[] sensors, Trigger[] triggers) {
            this.sensors = sensors;
            this.triggers = triggers;
        }

        @Override
        public String toString() {
            return "EnvSensorProps{" +
                    "sensors=" + Arrays.toString(sensors) +
                    ", triggers=" + Arrays.toString(triggers) +
                    '}';
        }
    };

    static class SwitchProps extends DevProps {
        private String[] devNames;

        public SwitchProps(String[] devNames) {
            this.devNames = devNames;
        }

        @Override
        public String toString() {
            return "SwitchProps{" +
                    "devNames=" + Arrays.toString(devNames) +
                    '}';
        }

        public String[] getDevNames() {
            return devNames;
        }

        public void setDevNames(String[] devNames) {
            this.devNames = devNames;
        }
    }

    static class Device {
        private String name;
        private int address;
        private int devType;
        DevProps devProps;

        public Device(String name, int address, int devType, DevProps cmdBody) {
            this.name = name;
            this.devType = devType;
            this.address = address;
            this.devProps = cmdBody;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getDevType() {
            return devType;
        }

        public void setDevType(int devType) {
            this.devType = devType;
        }

        public int getAddress() {
            return address;
        }

        public void setAddress(int address) {
            this.address = address;
        }

        public DevProps getDevProps() {
            return devProps;
        }

        public void setDevProps(DevProps cmdBody) {
            this.devProps = cmdBody;
        }
    }
}
