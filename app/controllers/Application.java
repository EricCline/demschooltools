package controllers;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.Expr;
import com.avaje.ebean.Expression;
import com.avaje.ebean.RawSql;
import com.avaje.ebean.RawSqlBuilder;
import com.avaje.ebean.SqlUpdate;
import com.feth.play.module.pa.PlayAuthenticate;
import com.typesafe.plugin.*;

import models.*;

import play.*;
import play.data.*;
import play.libs.Json;
import play.mvc.*;
import play.mvc.Http.Context;

@Security.Authenticated(Secured.class)
public class Application extends Controller {

    public static Date getDateFromString(String date_string) {
        if (!date_string.equals("")) {
            try
            {
                return new SimpleDateFormat("yyyy-MM-dd").parse(date_string);
            } catch (ParseException e) {
                return null;
            }
        } else {
            return null;
        }
    }

    public static List<Charge> getActiveSchoolMeetingReferrals() {
        return Charge.find.where().eq("referred_to_sm", true).eq("sm_decision", null).orderBy("id DESC").findList();
    }

    public static Result viewSchoolMeetingDecisions() {
        List<Charge> the_charges =
            Charge.find.where().
                eq("referred_to_sm", true).
                isNotNull("sm_decision").
                orderBy("id DESC").findList();
        return ok(views.html.view_sm_decisions.render(the_charges));
    }

    public static Result index() {
        List<Meeting> meetings = Meeting.find.orderBy("date DESC").findList();

        List<Charge> sm_charges = getActiveSchoolMeetingReferrals();

        Tag cur_student_tag = Tag.find.where().eq("title", "Current Student").findUnique();
        Tag staff_tag = Tag.find.where().eq("title", "Staff").findUnique();

        List<Person> people = getPeopleForTag(cur_student_tag.id);
        people.addAll(getPeopleForTag(staff_tag.id));
        Collections.sort(people, Person.SORT_DISPLAY_NAME);

        return ok(views.html.index.render(meetings, sm_charges, people,
            Rule.find.orderBy("title ASC").findList()));
    }

    public static Result viewMeeting(int meeting_id) {
        return ok(views.html.view_meeting.render(Meeting.find.byId(meeting_id)));
    }

    public static Result viewMeetingResolutionPlans(int meeting_id) {
        return ok(views.html.view_meeting_resolution_plans.render(Meeting.find.byId(meeting_id)));
    }

    public static Result getPersonHistory(Integer id) {
        Person p = Person.find.byId(id);
        return ok(views.html.person_history.render(p, new PersonHistory(p)));
    }

    public static Result getRuleHistory(Integer id) {
        Rule r = Rule.find.byId(id);
        return ok(views.html.rule_history.render(r, new RuleHistory(r)));
    }

    public static Result viewPersonHistory(Integer id) {
        Person p = Person.find.byId(id);
        return ok(views.html.view_person_history.render(p, new PersonHistory(p)));
    }

    public static Result viewRuleHistory(Integer id) {
        Rule r = Rule.find.byId(id);
        return ok(views.html.view_rule_history.render(r, new RuleHistory(r)));

	static Form<Person> personForm = Form.form(Person.class);
    static Form<Comment> commentForm = Form.form(Comment.class);
    static Form<Donation> donationForm = Form.form(Donation.class);

    public static Result index() {
        return redirect(routes.Application.people());
    }

    public static Result people() {
        List<Comment> recent_comments = Comment.find.orderBy("created DESC").setMaxRows(20).findList();
        return ok(views.html.index.render(Person.all(), recent_comments));
    }

    public static Result person(Integer id) {
        Person the_person = Person.find.ref(id);

        List<Person> family_members =
            Person.find.where().isNotNull("family").eq("family", the_person.family).
                ne("person_id", the_person.person_id).findList();

        Set<Integer> family_ids = new HashSet<Integer>();
        family_ids.add(the_person.person_id);
        for (Person family : family_members) {
            family_ids.add(family.person_id);
        }

        List<Comment> all_comments = Comment.find.where().in("person_id", family_ids).
            order("created DESC").findList();

        List<Donation> all_donations = Donation.find.where().in("person_id", family_ids).
            order("date DESC").findList();

        return ok(views.html.family.render(
            the_person,
            family_members,
            all_comments,
            all_donations));
    }

    public static Result jsonPeople(String query) {
		Expression search_expr = null;

		HashSet<Person> selected_people = new HashSet<Person>();
		boolean first_time = true;

		for (String term : query.split(" ")) {
			List<Person> people_matched_this_round;

			Expression this_expr =
				Expr.or(Expr.ilike("last_name", "%" + term + "%"),
				Expr.ilike("first_name", "%" + term + "%"));
			this_expr = Expr.or(this_expr,
				Expr.ilike("address", "%" + term + "%"));
			this_expr = Expr.or(this_expr,
				Expr.ilike("email", "%" + term + "%"));

			people_matched_this_round =
				Person.find.where(this_expr).findList();

			List<PhoneNumber> phone_numbers =
				PhoneNumber.find.where().ilike("number", "%" + term + "%").findList();
			for (PhoneNumber pn : phone_numbers) {
				people_matched_this_round.add(pn.owner);
			}

			if (first_time) {
				selected_people.addAll(people_matched_this_round);
			} else {
				selected_people.retainAll(people_matched_this_round);
			}

			first_time = false;
		}

        List<Map<String, String> > result = new ArrayList<Map<String, String> > ();
        for (Person p : selected_people) {
            HashMap<String, String> values = new HashMap<String, String>();
            String label = p.first_name;
            if (p.last_name != null) {
                label = label + " " + p.last_name;
            }
            values.put("label", label);
            values.put("id", "" + p.person_id);
            result.add(values);
        }

        return ok(Json.stringify(Json.toJson(result)));
    }

    public static Result jsonTags(String term, Integer personId) {
        String like_arg = "%" + term + "%";
        List<Tag> selected_tags =
            Tag.find.where().ilike("title", "%" + term + "%").findList();

        List<Tag> existing_tags = Person.find.byId(personId).tags;

        List<Map<String, String> > result = new ArrayList<Map<String, String> > ();
        for (Tag t : selected_tags) {
            if (existing_tags == null || !existing_tags.contains(t)) {
                HashMap<String, String> values = new HashMap<String, String>();
                values.put("label", t.title);
                values.put("id", "" + t.id);
                result.add(values);
            }
        }

        HashMap<String, String> values = new HashMap<String, String>();
        values.put("label", "Create new tag: " + term);
        values.put("id", "-1");
        result.add(values);

        return ok(Json.stringify(Json.toJson(result)));
    }

    public static Result addTag(Integer tagId, String title, Integer personId) {
        Person p = Person.find.byId(personId);
        if (p == null) {
            return badRequest();
        }

        Tag the_tag;
        if (tagId == null) {
            the_tag = Tag.create(title);
        } else {
            the_tag = Tag.find.ref(tagId);
        }

        PersonTag pt = PersonTag.create(
            the_tag,
            p,
            getCurrentUser());

        p.tags.add(the_tag);
        return ok(views.html.tag_fragment.render(the_tag, p));
    }

    public static Result removeTag(Integer person_id, Integer tag_id) {
        Ebean.createSqlUpdate("DELETE from person_tag where person_id=" + person_id +
            " AND tag_id=" + tag_id).execute();

        return ok();
    }

    static List<Person> getPeopleForTag(Integer id)
    {
        RawSql rawSql = RawSqlBuilder
            .parse("SELECT person.person_id, person.first_name, person.last_name, person.display_name from "+
                   "person join person_tag pt on person.person_id=pt.person_id "+
                  "join tag on pt.tag_id=tag.id")
            .columnMapping("person.person_id", "person_id")
        .columnMapping("person.first_name", "first_name")
        .columnMapping("person.last_name", "last_name")
		.columnMapping("person.display_name", "display_name")
            .create();

        return Ebean.find(Person.class).setRawSql(rawSql).
            where().eq("tag.id", id).orderBy("person.last_name, person.first_name").findList();
    }

    public static String jsonPeople(String term) {
        Tag cur_student_tag = Tag.find.where().eq("title", "Current Student").findUnique();
        Tag staff_tag = Tag.find.where().eq("title", "Staff").findUnique();

        List<Person> people = getPeopleForTag(cur_student_tag.id);
        people.addAll(getPeopleForTag(staff_tag.id));
        Collections.sort(people, Person.SORT_DISPLAY_NAME);

		term = term.toLowerCase();

        List<Map<String, String> > result = new ArrayList<Map<String, String> > ();
        for (Person p : people) {
            if (p.searchStringMatches(term)) {
                HashMap<String, String> values = new HashMap<String, String>();
                values.put("label", p.getDisplayName());
                values.put("id", "" + p.person_id);
                result.add(values);
            }
        }

        return Json.stringify(Json.toJson(result));
    }

    public static String jsonRules(String term) {
		term = term.toLowerCase();

        List<Rule> rules = Rule.find.where().eq("removed", false).orderBy("title ASC").findList();

        List<Map<String, String> > result = new ArrayList<Map<String, String> > ();
        for (Rule r : rules) {
            if (r.title.toLowerCase().contains(term)) {
                HashMap<String, String> values = new HashMap<String, String>();
                values.put("label", r.title);
                values.put("id", "" + r.id);
                result.add(values);
            }
        }

        return Json.stringify(Json.toJson(result));
    }

    public static String formatDateShort(Date d) {
        return new SimpleDateFormat("MM/dd").format(d);
    }

    public static String formatMeetingDate(Date d) {
        return new SimpleDateFormat("EE--MMMM dd, yyyy").format(d);
    }

	public static String yymmddDate(Date d ) {
		return new SimpleDateFormat("yyyy-M-d").format(d);
	}

    public static String currentUsername() {
        return Context.current().request().username();
    }

    public static boolean isUserEditor(String username) {
        return username != null && username.contains("@");
    }

    public static boolean isCurrentUserEditor() {
        return isUserEditor(currentUsername());
    }

    public static Configuration getConfiguration() {
        return Play.application().configuration().getConfig("jcdb");
    }

    public static String getRemoteIp() {
        Context ctx = Context.current();
        Configuration conf = getConfiguration();

        if (conf.getBoolean("heroku_ips")) {
            String header = ctx.request().getHeader("X-Forwarded-For");
            if (header == null) {
                return "unknown-ip";
            }
            String splits[] = header.split("[, ]");
            return splits[splits.length - 1];
        } else {
            return ctx.request().remoteAddress();
        }

	public static Result viewTag(Integer id) {
        Tag the_tag = Tag.find.byId(id);

        List<Person> people = getPeopleForTag(id);

        Set<Person> people_with_family = new HashSet<Person>();
        for (Person p : people) {
            if (p.family != null) {
                people_with_family.addAll(p.family.family_members);
            }
        }

        if (the_tag.use_student_display)
        {
            return viewIntentToEnroll(the_tag, people, people_with_family);
        }
        else
        {
            return ok(views.html.tag.render(the_tag, people, people_with_family));
        }
    }

    public static Result viewIntentToEnroll(Tag the_tag, List<Person> students,
        Set<Person> family_members)
    {
        return ok(views.html.intent_to_enroll_tag.render(the_tag, students, family_members));
    }

    public static User getCurrentUser() {
        return User.findByAuthUserIdentity(
            PlayAuthenticate.getUser(Context.current().session()));
    }

    public static Result newPerson() {
        return ok(views.html.new_person.render(personForm));
    }

    public static Result makeNewPerson() {
        Form<Person> filledForm = personForm.bindFromRequest();
        if(filledForm.hasErrors()) {
            return badRequest(
                views.html.new_person.render(filledForm)
            );
        } else {
            Person new_person = Person.create(filledForm);
            return redirect(routes.Application.person(new_person.person_id));
        }
    }

    public static Result deletePerson(Integer id) {
        Person.delete(id);
        return redirect(routes.Application.people());
    }

    public static Result editPerson(Integer id) {
        return ok(views.html.edit_person.render(Person.find.ref(id).fillForm()));
    }

    public static Result savePersonEdits() {
        return redirect(routes.Application.person(Person.updateFromForm(personForm.bindFromRequest()).person_id));
    }

    public static String getInitials(Person p) {
        String result = "";
        if (p.first_name != null && p.first_name.length() > 0) {
            result += p.first_name.charAt(0);
        }
        if (p.last_name != null && p.last_name.length() > 0) {
            result += p.last_name.charAt(0);
        }
        return result;
    }

    public static Result addComment() {
        Form<Comment> filledForm = commentForm.bindFromRequest();
        Comment new_comment = new Comment();

        new_comment.person = Person.find.byId(Integer.parseInt(filledForm.field("person").value()));
        new_comment.user = getCurrentUser();
        new_comment.message = filledForm.field("message").value();

        String task_id_string = filledForm.field("comment_task_ids").value();

        if (task_id_string.length() > 0 || new_comment.message.length() > 0) {
            new_comment.save();

            String[] task_ids = task_id_string.split(",");
            for (String id_string : task_ids) {
                if (!id_string.isEmpty()) {
                    int id = Integer.parseInt(id_string);
                    if (id >= 1) {
                        CompletedTask.create(Task.find.byId(id), new_comment);
                    }
                }
            }

            if (filledForm.field("send_email").value() != null) {
                MailerAPI mail = play.Play.application().plugin(MailerPlugin.class).email();
                mail.setSubject("Papal comment: " + new_comment.user.name + " & " + getInitials(new_comment.person));
                mail.addRecipient("TRVS Staff <staff@threeriversvillageschool.org>");
                mail.addFrom("Papal DB <noreply@threeriversvillageschool.org>");
                mail.sendHtml(views.html.comment_email.render(Comment.find.byId(new_comment.id)).toString());
            }

            return ok(views.html.comment_fragment.render(Comment.find.byId(new_comment.id), false));
        } else {
            return ok();
        }
    }

    public static Result addDonation() {
        Form<Donation> filledForm = donationForm.bindFromRequest();
        Donation new_donation = new Donation();

        new_donation.person = Person.find.byId(Integer.parseInt(filledForm.field("person").value()));
        new_donation.description = filledForm.field("description").value();
        new_donation.dollar_value = Float.parseFloat(filledForm.field("dollar_value").value());

        try
        {
            new_donation.date = new SimpleDateFormat("yyyy-MM-dd").parse(filledForm.field("date").value());
            // Set time to 12 noon so that time zone issues won't bump us to the wrong day.
            new_donation.date.setHours(12);
        }
        catch (ParseException e)
        {
            new_donation.date = new Date();
        }

        new_donation.is_cash = filledForm.field("donation_type").value().equals("Cash");
        if (filledForm.field("needs_thank_you").value() != null) {
            new_donation.thanked = !filledForm.field("needs_thank_you").value().equals("on");
        } else {
            new_donation.thanked = true;
        }

        if (filledForm.field("needs_indiegogo_reward").value() != null) {
            new_donation.indiegogo_reward_given = !filledForm.field("needs_indiegogo_reward").value().equals("on");
        } else {
            new_donation.indiegogo_reward_given = true;
        }

        new_donation.save();

        return ok(views.html.donation_fragment.render(Donation.find.byId(new_donation.id)));
    }

    public static Result donationThankYou(int id)
    {
        Donation d = Donation.find.byId(id);
        d.thanked = true;
        d.thanked_by_user = getCurrentUser();
        d.thanked_time = new Date();

        d.save();
        return ok();
    }

    public static Result donationIndiegogoReward(int id)
    {
        Donation d = Donation.find.byId(id);
        d.indiegogo_reward_given = true;
        d.indiegogo_reward_by_user = getCurrentUser();
        d.indiegogo_reward_given_time = new Date();

        d.save();
        return ok();
    }

    public static Result donationsNeedingThankYou()
    {
        List<Donation> donations = Donation.find.where().eq("thanked", false).orderBy("date DESC").findList();

        return ok(views.html.donation_list.render("Donations needing thank you", donations));
    }

    public static Result donationsNeedingIndiegogo()
    {
        List<Donation> donations = Donation.find.where().eq("indiegogo_reward_given", false).orderBy("date DESC").findList();

        return ok(views.html.donation_list.render("Donations needing Indiegogo reward", donations));
    }

    public static Result donations()
    {
        return ok(views.html.donation_list.render("All donations", Donation.find.orderBy("date DESC").findList()));
    }

    public static int calcAge(Person p) {
        return (int)((new Date().getTime() - p.dob.getTime()) / 1000 / 60 / 60 / 24 / 365.25);
    }

    public static int calcAgeAtBeginningOfSchool(Person p) {
		if (p.dob == null) {
			return -1;
		}
        return (int)((new Date(113, 8, 29).getTime() - p.dob.getTime()) / 1000 / 60 / 60 / 24 / 365.25);
    }

    public static Configuration getConfiguration() {
		return Play.application().configuration().getConfig("school_crm");
	}

    public static String formatDob(Date d) {
		if (d == null) {
			return "---";
		}
        return new SimpleDateFormat("MM/dd/yy").format(d);
    }

    public static String formatDate(Date d) {
        d = new Date(d.getTime() +
            (getConfiguration().getInt("time_zone_offset") * 1000L * 60 * 60));
        Date now = new Date();

		long diffHours = (now.getTime() - d.getTime()) / 1000 / 60 / 60;

        // String format = "EEE MMMM d, h:mm a";
		String format;

		if (diffHours < 24) {
			format = "h:mm a";
		} else if (diffHours < 24 * 7) {
			format = "EEEE, MMMM d";
		} else {
            format = "MM/d/yy";
        }
        return new SimpleDateFormat(format).format(d);
    }

    public static Result viewTaskList(Integer id) {
        TaskList list = TaskList.find.byId(id);
        List<Person> people = getPeopleForTag(list.tag.id);

        return ok(views.html.task_list.render(list, people));
    }
}
