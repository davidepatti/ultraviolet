package misc.json;

import java.util.ArrayList;

public class JsonArray extends ArrayList<Object> {
    public String toJsonString() {
        return Json.stringify(this);
    }
}
