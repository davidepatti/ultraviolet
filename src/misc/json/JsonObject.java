package misc.json;

import java.util.LinkedHashMap;

public class JsonObject extends LinkedHashMap<String, Object> {
    public String toJsonString() {
        return Json.stringify(this);
    }
}
