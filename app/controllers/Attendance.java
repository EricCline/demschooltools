package controllers;

import java.io.*;
import java.sql.Time;
import java.text.SimpleDateFormat;
import java.util.*;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.Expr;
import com.avaje.ebean.Expression;
import com.avaje.ebean.FetchConfig;
import com.avaje.ebean.RawSql;
import com.avaje.ebean.RawSqlBuilder;
import com.avaje.ebean.SqlUpdate;
import com.csvreader.CsvWriter;

import models.*;

import play.*;
import play.data.*;
import play.mvc.*;
import play.mvc.Http.Context;

@Security.Authenticated(EditorSecured.class)
@With(DumpOnError.class)
public class Attendance extends Controller {

    public static final String CACHE_INDEX = "Attendance-index-";

    static Form<AttendanceCode> code_form = Form.form(AttendanceCode.class);

    // @OrgCached(CACHE_INDEX)
    public static Result index() {
        Map<Person, AttendanceStats> person_to_stats =
            new HashMap<Person, AttendanceStats>();

        Map<String, AttendanceCode> codes_map = getCodesMap();

        List<AttendanceDay> days =
            AttendanceDay.find.where()
                .eq("person.organization", OrgConfig.get().org)
                .ge("day", Application.getStartOfYear())
                .findList();

        for (AttendanceDay day : days) {
            if (!person_to_stats.containsKey(day.person)) {
                person_to_stats.put(day.person, new AttendanceStats());
            }

            AttendanceStats stats = person_to_stats.get(day.person);
            if (day.code != null || day.start_time == null || day.end_time == null) {
                stats.incrementCodeCount(codes_map.get(day.code));
            } else {
                stats.total_hours += day.getHours();
                stats.days_present++;
            }
        }

        List<Person> all_people = new ArrayList<Person>(person_to_stats.keySet());
        Collections.sort(all_people, Person.SORT_DISPLAY_NAME);

        List<String> all_codes = new ArrayList<String>(codes_map.keySet());

        return ok(views.html.attendance_index.render(
            all_people, person_to_stats, all_codes, codes_map));
    }

    public static Result viewWeek(String date) {
        Calendar start_date = Utils.parseDateOrNow(date);
        Utils.adjustToPreviousDay(start_date, Calendar.MONDAY);

        Calendar end_date = (Calendar)start_date.clone();
        end_date.add(Calendar.DAY_OF_MONTH, 5);

        List<AttendanceDay> days =
            AttendanceDay.find.where()
                .eq("person.organization", OrgConfig.get().org)
                .ge("day", start_date.getTime())
                .lt("day", end_date.getTime())
                .order("day ASC")
                .findList();

        Map<Person, List<AttendanceDay>> person_to_days =
            new HashMap<Person, List<AttendanceDay>>();

        for (AttendanceDay day : days) {
            List<AttendanceDay> list = person_to_days.containsKey(day.person)
                ? person_to_days.get(day.person)
                : new ArrayList<AttendanceDay>();

            list.add(day);
            person_to_days.put(day.person, list);
        }

        List<AttendanceWeek> weeks =
            AttendanceWeek.find.where()
                .eq("person.organization", OrgConfig.get().org)
                .eq("monday", start_date.getTime())
                .findList();

        Map<Person, AttendanceWeek> person_to_week =
            new HashMap<Person, AttendanceWeek>();

        for (AttendanceWeek week : weeks) {
            person_to_week.put(week.person, week);
        }

        List<Person> all_people = new ArrayList<Person>();
        for (Person p : person_to_days.keySet()) {
            if (!all_people.contains(p)) {
                all_people.add(p);
            }
        }
        for (Person p : person_to_week.keySet()) {
            if (!all_people.contains(p)) {
                all_people.add(p);
            }
        }
        Collections.sort(all_people, Person.SORT_DISPLAY_NAME);

        Map<String, AttendanceCode> codes = getCodesMap();

        return ok(views.html.attendance_week.render(
            start_date.getTime(),
            codes,
            all_people,
            person_to_days,
            person_to_week));
    }

    public static Map<String, AttendanceCode> getCodesMap() {
        Map<String, AttendanceCode> codes = new HashMap<String, AttendanceCode>();

        for (AttendanceCode code : AttendanceCode.all(OrgConfig.get().org)) {
            codes.put(code.code, code);
        }

        return codes;
    }

    public static Result viewCodes() {
        return ok(views.html.attendance_codes.render(
            AttendanceCode.all(OrgConfig.get().org),
            code_form));
    }

    public static Result newCode() {
        AttendanceCode ac = AttendanceCode.create(OrgConfig.get().org);
        Form<AttendanceCode> filled_form = code_form.bindFromRequest();
        ac.edit(filled_form);

        return redirect(routes.Attendance.viewCodes());
    }

    public static Result editCode(Integer code_id) {
        AttendanceCode ac = AttendanceCode.findById(code_id);
        Form<AttendanceCode> filled_form = code_form.fill(ac);
        return ok(views.html.edit_attendance_code.render(filled_form));
    }

    public static Result saveCode() {
        Form<AttendanceCode> filled_form = code_form.bindFromRequest();
        AttendanceCode ac = AttendanceCode.findById(
            Integer.parseInt(filled_form.field("id").value()));
        ac.edit(filled_form);

        return redirect(routes.Attendance.viewCodes());
    }

    public static String formatTime(Time t) {
        if (t != null) {
            return new SimpleDateFormat("h:mm a").format(t);
        } else {
            return "---";
        }
    }

    public static int getDaysPresent(List<AttendanceDay> days) {
        int result = 0;
        for (AttendanceDay day : days) {
            if (day.code == null && day.start_time != null && day.end_time != null) {
                result++;
            }
        }

        return result;
    }

    public static double getTotalHours(List<AttendanceDay> days, AttendanceWeek week) {
        double result = 0;
        for (AttendanceDay day : days) {
            if (day.code == null && day.start_time != null && day.end_time != null) {
                result += day.getHours();
            }
        }

        if (week != null) {
            result += week.extra_hours;
        }

        return result;
    }

    public static double getAverageHours(List<AttendanceDay> days, AttendanceWeek week) {
        int daysPresent = getDaysPresent(days);
        if (daysPresent == 0) {
            return 0;
        }

        return getTotalHours(days, week) / (double)daysPresent;
    }

    public static String format(double d) {
        return String.format("%,.1f", d);
    }
}
