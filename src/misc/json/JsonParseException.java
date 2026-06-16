package misc.json;

import java.io.IOException;

public class JsonParseException extends IOException {
    public JsonParseException(String message) {
        super(message);
    }
}
