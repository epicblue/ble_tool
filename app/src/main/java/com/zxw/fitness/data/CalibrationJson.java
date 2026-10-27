package com.zxw.fitness.data;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;

import org.json.JSONException;
import org.json.JSONObject;

/** 账号设置与训练快照共用的 JSON 格式。 */
final class CalibrationJson {
    private CalibrationJson() { }

    static JSONObject write(PersonalCalibration c) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("exercise", c.getType().getKey());
        o.put("variant", c.getVariant());
        o.put("targetDepth", c.getTargetDepth());
        o.put("startingAngle", c.getStartingAngle());
        o.put("capturedAngle", c.getCapturedAngle());
        o.put("capturedMovement", c.getCapturedMovement());
        o.put("maxForm", c.getMaxForm());
        o.put("minHipAngle", c.getMinHipAngle());
        o.put("minMovement", c.getMinMovement());
        o.put("allowKneeling", c.isKneelingAllowed());
        o.put("formWarning", c.hasFormWarning());
        return o;
    }

    static PersonalCalibration read(JSONObject o) {
        if (o == null) return null;
        String key = o.optString("exercise", "");
        ExerciseType type = null;
        for (ExerciseType candidate : ExerciseType.values()) {
            if (candidate.getKey().equals(key)) {
                type = candidate;
                break;
            }
        }
        if (type == null || !type.supportsPersonalCalibration()) return null;
        PersonalCalibration result = new PersonalCalibration(type, o.optString("variant", ""),
                (float) o.optDouble("targetDepth", Double.NaN),
                (float) o.optDouble("capturedAngle", Double.NaN),
                (float) o.optDouble("capturedMovement", Double.NaN),
                (float) o.optDouble("maxForm", Double.NaN),
                (float) o.optDouble("minHipAngle", Double.NaN),
                (float) o.optDouble("minMovement", Double.NaN),
                o.optBoolean("allowKneeling", false), o.optBoolean("formWarning", false),
                (float) o.optDouble("startingAngle", PersonalCalibration.defaultStartingAngle(type)));
        return result.isValid() ? result : null;
    }
}
