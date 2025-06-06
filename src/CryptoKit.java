import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;

public class CryptoKit {


    /***************************************************************************/
    public static byte[] encodeVarStr(byte[] str) {
        return CryptoKit.addLenPrefix(str);
    }

    /***************************************************************************/
    public static byte[] readVarStr(byte[] str) {
        var bis = new ByteArrayInputStream(str);

        long l = readVarint(bis);
        try {
            return bis.readNBytes((int) l);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    /***************************************************************************/
    public static byte[] addLenPrefix(byte[] bytes) {
        long len = bytes.length;
        var varint = CryptoKit.encodeVarint(len);
        var bos = new ByteArrayOutputStream();
        try {
            assert varint != null;
            bos.write(varint);
            bos.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return bos.toByteArray();
    }

    /***************************************************************************/
    public static String bytesToHexString(byte [] bytes) {
        final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
    /***************************************************************************/
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        // add missing leading 0 to make total amount even
        if (len%2!=0) {
            s = "0"+s;
            len++;
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
    /***************************************************************************/
    public static String hexStringToAscii(String hexStr) {
        StringBuilder output = new StringBuilder();

        for (int i = 0; i < hexStr.length(); i += 2) {
            String str = hexStr.substring(i, i + 2);
            output.append((char) Integer.parseInt(str, 16));
        }

        return output.toString();
    }
    /***************************************************************************/
    public static String bytesToAscii(byte[] bytes) {
        return hexStringToAscii(bytesToHexString(bytes));
    }

    /***************************************************************************/
    public static byte[] RIPEMD160(byte[] r) {
        return Ripemd160.getHash(r);
    }
    /***************************************************************************/
    public static byte[] sha256(byte[] b) {
        MessageDigest digester = null;
        try {
            digester = MessageDigest.getInstance("SHA-256");
        } catch (
                NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        assert digester != null;
        return digester.digest(b);
    }

    /***************************************************************************/
    public static byte[] hash256(byte[] b) {
        return sha256(sha256(b));
    }


    /***************************************************************************/
    public static byte[] hash160(byte[] b) {
        return RIPEMD160(sha256(b));
    }

    /***************************************************************************/
    public static byte[] hash256(String message) {
        return hash256(message.getBytes(StandardCharsets.UTF_8));
    }

    /***************************************************************************/
    public static byte[] asciiStringToBytes(String s ) {
        return  s.getBytes(StandardCharsets.UTF_8);
    }


    /***************************************************************************/
    public static String encodeBase58(byte[] s) {
        String BASE58_AlPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        int count = 0;
        for (byte b: s) {
            if (b==0) count++;
            else break;
        }
        // TODO: fix this
        if (count>1)
            System.out.println("***WARNING on encodeBase58 leading zero bytes");

        var num = new BigInteger(1,s);
        StringBuilder prefix = new StringBuilder();
        for (int c=0;c<count;c++)
            prefix.insert(0, "1");

        StringBuilder result = new StringBuilder();
        BigInteger[] num_mod;
        while (num.compareTo(BigInteger.ZERO)>0) {
            num_mod = num.divideAndRemainder(BigInteger.valueOf(58));
            num = num_mod[0];
            int mod = num_mod[1].intValue();
            result.insert(0, BASE58_AlPHABET.charAt(mod));
        }
        return prefix+ result.toString();
        //return result;
    }
    /***************************************************************************/
    // get the 20 bytes of the hashed160 public key from the encoded58 address
    public static byte[] decodeBase58(String address) {
        String BASE58_AlPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        var num = BigInteger.valueOf(0);

        for (int i=0;i<address.length();i++) {
            char c = address.charAt(i);
            num = num.multiply(BigInteger.valueOf(58));
            num = num.add(BigInteger.valueOf(BASE58_AlPHABET.indexOf(c)));
        }

        var combined = num.toByteArray();
        var cobined_hex = CryptoKit.bytesToHexString(combined);
        var len = combined.length;
        byte[] checksum_start = Arrays.copyOfRange(combined,len-4,len);
        var checksum_start_hex = CryptoKit.bytesToHexString(checksum_start);
        var original = Arrays.copyOfRange(combined,0,len-4);
        var computed_checksum = hash256(original);
        var computed_checksim_start = Arrays.copyOfRange(computed_checksum,0,4);

        if (!Arrays.equals(computed_checksim_start,checksum_start)) {
            System.out.println("ERROR: Bad address checksum!");
            System.out.println("Address: "+address);
            System.out.println("Computed checksum:"+ Arrays.toString(computed_checksim_start));
            System.out.println("Original checksum:"+ Arrays.toString(checksum_start));
        }

        // the first byte is the network prefix, the last four are the checksum

        return Arrays.copyOfRange(combined,1,len-4);
    }


    /***************************************************************************/
    public static String encodeBase58Checksum(byte[] b) {
        var hash_b = hash256(b);
        var bos = new ByteArrayOutputStream();
        bos.writeBytes(b);
        bos.write(hash_b[0]);
        bos.write(hash_b[1]);
        bos.write(hash_b[2]);
        bos.write(hash_b[3]);
        var res = bos.toByteArray();
        return encodeBase58(res);
    }

    /***************************************************************************/
    public static byte[] calcHmacSha256(byte[] secretKey, byte[] message) {
        byte[] hmacSha256;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey, "HmacSHA256");
            mac.init(secretKeySpec);
            hmacSha256 = mac.doFinal(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate hmac-sha256", e);
        }
        return hmacSha256;
    }

    /***************************************************************************/
    public static byte[] to32bytes(byte[] secret) {
        var bos = new ByteArrayOutputStream();
        for (int i=0;i<32-secret.length;i++)
            bos.write(0);
        bos.writeBytes(secret);
        return bos.toByteArray();
    }

    /***************************************************************************/
    public static BigInteger litteEndianBytesToInt(byte[] bytes) {

        var reversed_bytes = to32bytes(reverseBytes(bytes));
        return new BigInteger(reversed_bytes);
    }

    /***************************************************************************/
    public static byte[] intToLittleEndianBytes(long n) {
        return intToLittleEndianBytes(BigInteger.valueOf(n));
    }

    /***************************************************************************/
    public static byte[] intToLittleEndianBytes(BigInteger bi) {
        byte[] extractedBytes = bi.toByteArray();
        return reverseBytes(to32bytes(extractedBytes));
    }

    /***************************************************************************/
    public static BigInteger littleEndianBytesToInt(byte[] little_bytes) {
        byte[] reversed = to32bytes(reverseBytes(little_bytes));
        return new BigInteger(reversed);
    }

    /***************************************************************************/
    public static byte[] reverseBytes(byte[] bytes)  {
        int size = bytes.length;
        byte[] reversed_bytes = new byte[size];

        for (int i = 0; i< size; i++)
            reversed_bytes[size-1-i] = bytes[i];

        return reversed_bytes;

    }

    /***************************************************************************/
    public static byte[] concatBytes(byte[] data1, byte[] data2) {

        var data = new byte[data1.length+data2.length];

        System.arraycopy(data1, 0, data, 0, data1.length);
        System.arraycopy(data2, 0, data, data1.length, data2.length);
        return data;
    }

    /***************************************************************************/
    public static long readVarint(ByteArrayInputStream bis) {
        byte[] buffer;
        long n;
        long i = bis.read();

        try {
            if (i==0xfd) {
                buffer = bis.readNBytes(2);
                n = litteEndianBytesToInt(buffer).longValue();
                return n;
            }
            else if(i==0xfe) {
                buffer = bis.readNBytes(4);
                n = litteEndianBytesToInt(buffer).longValue();
                return n;
            }
            else if(i==0xff) {
                buffer = bis.readNBytes(8);
                n = litteEndianBytesToInt(buffer).longValue();
                return n;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return i;

    }

    /***************************************************************************/
    public static long readVarint(byte[] bytes) {
        var bis = new ByteArrayInputStream(bytes);
        return readVarint(bis);
    }

    /***************************************************************************/
    public static byte[] encodeVarint(long i) {
        byte[] buffer;
        byte[] result;
        byte prefix;
        var bos = new ByteArrayOutputStream();
        if (i<0xfd) {
            // single byte, not need to reorder little endian
            //return BigInteger.valueOf(i).toByteArray();
            return new byte[] {(byte)i};
        }
        else if (i<0x10000) {
            buffer = intToLittleEndianBytes(i);
            prefix = (byte)0xfd;
            bos.write(prefix);
            bos.write(buffer,0,2);
            result = bos.toByteArray();
            return result;
        }
        else if (i<0x100000000L) {
            buffer = intToLittleEndianBytes(i);
            prefix = (byte)0xfe;
            bos.write(prefix);
            bos.write(buffer,0,4);
            result = bos.toByteArray();
            return result;
        }
        else if (BigInteger.valueOf(i).compareTo(BigInteger.TWO.pow(64))<0) {
            buffer = intToLittleEndianBytes(i);
            prefix = (byte)0xff;
            bos.write(prefix);
            bos.write(buffer,0,8);
            result = bos.toByteArray();
            return result;
        }
        return null;
    }


    /***************************************************************************/
    public static double log2(int x) {
        return Math.log(x) / Math.log(2);
    }

    /***************************************************************************/
    public static String reverseByteString(String s) {
        var bytes = CryptoKit.hexStringToByteArray(s);
        return CryptoKit.bytesToHexString(CryptoKit.reverseBytes(bytes));
    }

    /***************************************************************************/
    static ArrayList<Boolean> bytesToBitField(byte[] some_bytes) {
        var flag_bits = new ArrayList<Boolean>();

        // notice: it's not a simple byte->binary conversion

        // bytes:
        // 10000010 11000001

        // bitfield
        // 01000001 10000011
        for (byte b: some_bytes) {
            for (int i=0;i<8;i++) {
                flag_bits.add((b & 1) == 1);
                b>>=1;
            }
        }

        return flag_bits;
    }
    /***************************************************************************/
    public static byte[] bitFieldToBytes(ArrayList<Boolean> bitfield) {
        if (bitfield.size()%8!=0) {
            throw new RuntimeException("Not multiple of 8");
        }

        var bytes = new byte[bitfield.size()/8];

        for (int b=0;b<bytes.length;b++) {
            for (int i=0;i<8;i++) {
                if (bitfield.get(b*8+i)) {
                    bytes[b] += (1<<i);
                }
            }
        }

        return bytes;
    }

    /***************************************************************************/
    public static ArrayList<Boolean> bytesToBitField(String some_bytes) {
        return bytesToBitField(hexStringToByteArray(some_bytes));
    }

    /***************************************************************************/
    public static ArrayList<Boolean> bitStringToBitField(String some_bits) {

        var bits = new ArrayList<Boolean>();

        for (int i=0;i<some_bits.length();i++) {
            if (some_bits.charAt(i)=='1')
                bits.add(true);
            else
                bits.add(false);
        }
        return bits;
    }

    /***************************************************************************/
    public static byte[] intToBigEndian(BigInteger n,int length) {

        var all_bytes = n.toByteArray();
        var extra_bytes = all_bytes.length-length;

        byte[] trimmed = null;

        if (extra_bytes>0)  {
            trimmed= Arrays.copyOfRange(all_bytes,extra_bytes,all_bytes.length);
            return trimmed;

        }
        else if (extra_bytes<0) {
            var bos = new ByteArrayOutputStream();
            extra_bytes = -extra_bytes;

            for (int i=0;i<extra_bytes;i++)
                bos.write(0);
            try {
                bos.write(all_bytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return bos.toByteArray();
        }

        return all_bytes;
    }

    /***************************************************************************/
    public static boolean isValidBIP32Path(String path ) {
        path = path.toLowerCase().strip().replace("'","h").replace("//","/");

        if (path.equals("m")) return true;
        if (!path.startsWith("m/")) return false;

        var sub_paths = path.substring(2).split("/");

        //https://bitcoin.stackexchange.com/a/92057
        if (sub_paths.length>256) return false;


        for (String subpath: sub_paths) {
            if (subpath.endsWith("h"))
                subpath = subpath.substring(0,subpath.length()-1);

            BigInteger intpath = new BigInteger(subpath,10);

            if (intpath.compareTo(BigInteger.ZERO)<0) return false;
            if (intpath.compareTo(BigInteger.TWO.pow(31))>0) return false;
        }

        return true;
    }

    public static String shortString(String input) {
        if (input == null || input.length() < 2) {
            return "";
        } else {
            String firstTwo = input.substring(0, 2);
            String lastTwo = input.substring(input.length() - 2);
            return firstTwo + ".." + lastTwo;
        }
    }


}


