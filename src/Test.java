import java.util.*;

public class Test {
    public static void main(String[] args) {
//        String string = "Man";
//
//        byte[] array = {11, -51, 6, -127, 127, 1, 1, 1, 3, 72, 85, 66, -85};
////         кодируем строку с помощью кодировщика `Base64`
//        Base64.Encoder encoder = Base64.getEncoder();
//        String encoded = encoder.encodeToString(array);
//        System.out.println("Encoded Data: " + encoded);
//
//        // декодируем закодированные данные
//        Base64.Decoder decoder = Base64.getDecoder();
//        byte[] decoded = decoder.decode(encoded);
//        System.out.println("Decoded Data: ");
//        Arrays.toString(decoded);
//
//        byte[] bytes = decoder.decode(encoded);
//        for (byte b : bytes)
//            System.out.println(b + " ");

//        ArrayList<Main.Payload> arrayList = Main.decodePackage("B7MGAiACBAOO");
//        System.out.println(arrayList);

//        ArrayList<Byte> arr = Main.encodeULEB128(4098);
//        System.out.println(arr);
//        System.out.println(Arrays.toString(Main.decodeULEB128(0, new byte[]{2, 32})));
//
//        System.out.println(Integer.toBinaryString((byte) -1));

        byte[] first = Base64.getUrlDecoder().decode("Dgb_fxUGAgdDTE9DSzAxsw");
        byte[] second = Base64.getUrlDecoder().decode("DwX_fxEFAghTT0NLRVQwMc0");
        String a = Base64.getUrlEncoder().withoutPadding().encodeToString(first) + Base64.getUrlEncoder().withoutPadding().encodeToString(second);
        System.out.println(a);
    }
}
