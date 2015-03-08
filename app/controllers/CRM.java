package controllers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.mail.*;
import javax.mail.internet.*;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.Expr;
import com.avaje.ebean.Expression;
import com.avaje.ebean.RawSql;
import com.avaje.ebean.RawSqlBuilder;
import com.avaje.ebean.SqlUpdate;
import com.ecwid.mailchimp.*;
import com.ecwid.mailchimp.method.v2_0.lists.ListMethodResult;
import com.feth.play.module.pa.PlayAuthenticate;
import com.typesafe.plugin.*;

import models.*;

import play.*;
import play.data.*;
import play.libs.Json;
import play.mvc.*;
import play.mvc.Http.Context;

@Security.Authenticated(EditorSecured.class)
public class CRM extends Controller {

	static Form<Person> personForm = Form.form(Person.class);
    static Form<Comment> commentForm = Form.form(Comment.class);
    static Form<Donation> donationForm = Form.form(Donation.class);

    public static Result people() {
        List<Comment> recent_comments = Comment.find
            .where().eq("person.organization", Organization.getByHost())
            .orderBy("created DESC").setMaxRows(20).findList();

        return ok(views.html.people_index.render(Person.all(), recent_comments));
    }

    public static Result person(Integer id) {
        Person the_person = Person.findById(id);

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
				Person.find.where(this_expr).where().eq("organization", Organization.getByHost())
                    .findList();

			List<PhoneNumber> phone_numbers =
				PhoneNumber.find.where().ilike("number", "%" + term + "%")
                    .eq("owner.organization", Organization.getByHost())
                    .findList();
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

    // personId should be -1 if the client doesn't care about a particular
    // person, but just wants a list of available tags. In that case,
    // the "create new tag" functionality is also disabled.
    public static Result jsonTags(String term, Integer personId) {
        String like_arg = "%" + term + "%";
        List<Tag> selected_tags =
            Tag.find.where()
                .eq("organization", Organization.getByHost())
                .ilike("title", "%" + term + "%").findList();

		List<Tag> existing_tags = null;
        if (personId >= 0) {
            Person p = Person.findById(personId);
            if (p != null) {
                existing_tags = p.tags;
            }
        }

        List<Map<String, String> > result = new ArrayList<Map<String, String> > ();
        for (Tag t : selected_tags) {
            if (existing_tags == null || !existing_tags.contains(t)) {
                HashMap<String, String> values = new HashMap<String, String>();
                values.put("label", t.title);
                values.put("id", "" + t.id);
                result.add(values);
            }
        }

        if (personId >= 0) {
            HashMap<String, String> values = new HashMap<String, String>();
            values.put("label", "Create new tag: " + term);
            values.put("id", "-1");
            result.add(values);
        }

        return ok(Json.stringify(Json.toJson(result)));
    }

	public static Collection<Person> getTagMembers(Integer tagId, String familyMode) {
        List<Person> people = getPeopleForTag(tagId);

        Set<Person> selected_people = new HashSet<Person>();

		selected_people.addAll(people);

		if (!familyMode.equals("just_tags")) {
			for (Person p : people) {
				if (p.family != null) {
					selected_people.addAll(p.family.family_members);
				}
			}
		}

		if (familyMode.equals("family_no_kids")) {
			Set<Person> no_kids = new HashSet<Person>();
			for (Person p2 : selected_people) {
				if (p2.dob == null ||
					CRM.calcAge(p2) > 18) {
					no_kids.add(p2);
				}
			}
			selected_people = no_kids;
		}

		return selected_people;
	}

	public static Result renderTagMembers(Integer tagId, String familyMode) {
        Tag the_tag = Tag.findById(tagId);
		return ok(views.html.to_address_fragment.render(the_tag.title,
			getTagMembers(tagId, familyMode)));
	}

    public static Result addTag(Integer tagId, String title, Integer personId) {
        Person p = Person.findById(personId);
        if (p == null) {
            return badRequest();
        }

        Tag the_tag;
        if (tagId == null) {
            the_tag = Tag.create(title);
        } else {
            the_tag = Tag.find.ref(tagId);
        }

        PersonTag pt = PersonTag.create(the_tag, p);

        PersonTagChange ptc = PersonTagChange.create(
            the_tag,
            p,
            Application.getCurrentUser(),
            true);

        p.tags.add(the_tag);
        return ok(views.html.tag_fragment.render(the_tag, p));
    }

    public static Result removeTag(Integer person_id, Integer tag_id) {
        if (Ebean.createSqlUpdate("DELETE from person_tag where person_id=" + person_id +
            " AND tag_id=" + tag_id).execute() == 1) {
            PersonTagChange ptc = PersonTagChange.create(
                Tag.find.ref(tag_id),
                Person.findById(person_id),
                Application.getCurrentUser(),
                false);
        }

        return ok();
    }

    public static List<Person> getPeopleForTag(Integer id) {
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

	public static Result viewTag(Integer id) {
        Tag the_tag = Tag.findById(id);

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
            return redirect(routes.CRM.person(new_person.person_id));
        }
    }

	static Email getPendingEmail() {
		return Email.find.where()
            .eq("organization", Organization.getByHost())
            .eq("deleted", false).eq("sent", false).orderBy("id ASC").setMaxRows(1).findUnique();
	}

	public static boolean hasPendingEmail() {
		return getPendingEmail() != null;
	}

	public static Result viewPendingEmail() {
		Email e = getPendingEmail();
		if (e == null) {
			return redirect(routes.CRM.people());
		}

        Tag staff_tag = Tag.find.where()
            .eq("organization", Organization.getByHost())
            .eq("title", "Staff").findUnique();
        List<Person> people = getPeopleForTag(staff_tag.id);

        ArrayList<String> test_addresses = new ArrayList<String>();
        for (Person p : people) {
            test_addresses.add(p.email);
        }
		test_addresses.add("staff@threeriversvillageschool.org");

        ArrayList<String> from_addresses = new ArrayList<String>();
        from_addresses.add("office@threeriversvillageschool.org");
        from_addresses.add("evan@threeriversvillageschool.org");
        from_addresses.add("jmp@threeriversvillageschool.org");
        from_addresses.add("jancey@threeriversvillageschool.org");
        from_addresses.add("info@threeriversvillageschool.org");
        from_addresses.add("staff@threeriversvillageschool.org");

        e.parseMessage();
		return ok(views.html.view_pending_email.render(e, test_addresses, from_addresses));
	}

    public static Result sendTestEmail() {
        final Map<String, String[]> values = request().body().asFormUrlEncoded();
        Email e = Email.findById(Integer.parseInt(values.get("id")[0]));
        e.parseMessage();

		try {
			MimeMessage to_send = new MimeMessage(e.parsedMessage);
			to_send.addRecipient(Message.RecipientType.TO,
						new InternetAddress(values.get("dest_email")[0]));
			to_send.setFrom(new InternetAddress("Papal DB <noreply@threeriversvillageschool.org>"));
			Transport.send(to_send);
		} catch (MessagingException ex) {
			ex.printStackTrace();
		}

        return ok();
    }

    public static Result sendEmail() {
        final Map<String, String[]> values = request().body().asFormUrlEncoded();
        Email e = Email.findById(Integer.parseInt(values.get("id")[0]));
        e.parseMessage();

		int tagId = Integer.parseInt(values.get("tagId")[0]);
        Tag theTag = Tag.findById(tagId);
		String familyMode = values.get("familyMode")[0];
		Collection<Person> recipients = getTagMembers(tagId, familyMode);

		boolean hadErrors = false;

		for (Person p : recipients) {
			if (p.email != null && !p.email.equals("")) {
				try {
					MimeMessage to_send = new MimeMessage(e.parsedMessage);
					to_send.addRecipient(Message.RecipientType.TO,
								new InternetAddress(p.email, p.first_name + " " + p.last_name));
					to_send.setFrom(new InternetAddress(values.get("from")[0]));
					Transport.send(to_send);
				} catch (MessagingException ex) {
					ex.printStackTrace();
					hadErrors = true;
                } catch (UnsupportedEncodingException ex) {
                    ex.printStackTrace();
                    hadErrors = true;
				}
			}
		}

		// Send confirmation email
		try {
			MimeMessage to_send = new MimeMessage(e.parsedMessage);
			to_send.addRecipient(Message.RecipientType.TO,
						new InternetAddress("Staff <staff@threeriversvillageschool.org>"));
			String subject = "(Sent to " + theTag.title + " " + familyMode + ") " + to_send.getSubject();
			if (hadErrors) {
				subject = "***ERRORS*** " + subject;
			}
			to_send.setSubject(subject);
			to_send.setFrom(new InternetAddress(values.get("from")[0]));
            Transport.send(to_send);
		} catch (MessagingException ex) {
			ex.printStackTrace();
		}

		e.delete();
        return ok();
    }

    public static Result deleteEmail() {
        final Map<String, String[]> values = request().body().asFormUrlEncoded();
        Email e = Email.findById(Integer.parseInt(values.get("id")[0]));
        e.delete();

        return ok();
    }

    public static Result deletePerson(Integer id) {
        Person.delete(id);
        return redirect(routes.CRM.people());
    }

    public static Result editPerson(Integer id) {
        return ok(views.html.edit_person.render(Person.findById(id).fillForm()));
    }

    public static Result savePersonEdits() {
        return redirect(routes.CRM.person(Person.updateFromForm(personForm.bindFromRequest()).person_id));
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

        new_comment.person = Person.findById(Integer.parseInt(filledForm.field("person").value()));
        new_comment.user = Application.getCurrentUser();
        new_comment.message = filledForm.field("message").value();

        String task_id_string = filledForm.field("comment_task_ids").value();

        if (task_id_string.length() > 0 || new_comment.message.length() > 0) {
            new_comment.save();

            String[] task_ids = task_id_string.split(",");
            for (String id_string : task_ids) {
                if (!id_string.isEmpty()) {
                    int id = Integer.parseInt(id_string);
                    if (id >= 1) {
                        CompletedTask.create(Task.findById(id), new_comment);
                    }
                }
            }

            if (filledForm.field("send_email").value() != null) {
                play.libs.mailer.Email mail = new play.libs.mailer.Email();
                mail.setSubject("Papal comment: " + new_comment.user.name + " & " + getInitials(new_comment.person));
                mail.addTo("TRVS Staff <staff@threeriversvillageschool.org>");
                mail.setFrom("Papal DB <noreply@threeriversvillageschool.org>");
                mail.setBodyHtml(views.html.comment_email.render(Comment.find.byId(new_comment.id)).toString());
                play.libs.mailer.MailerPlugin.send(mail);
            }

            return ok(views.html.comment_fragment.render(Comment.find.byId(new_comment.id), false));
        } else {
            return ok();
        }
    }

    public static Result addDonation() {
        Form<Donation> filledForm = donationForm.bindFromRequest();
        Donation new_donation = new Donation();

        new_donation.person = Person.findById(Integer.parseInt(filledForm.field("person").value()));
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
        d.thanked_by_user = Application.getCurrentUser();
        d.thanked_time = new Date();

        d.save();
        return ok();
    }

    public static Result donationIndiegogoReward(int id)
    {
        Donation d = Donation.findById(id);
        d.indiegogo_reward_given = true;
        d.indiegogo_reward_by_user = Application.getCurrentUser();
        d.indiegogo_reward_given_time = new Date();

        d.save();
        return ok();
    }

    public static Result donationsNeedingThankYou()
    {
        List<Donation> donations = Donation.find.where()
            .eq("person.organization", Organization.getByHost())
            .eq("thanked", false).orderBy("date DESC").findList();

        return ok(views.html.donation_list.render("Donations needing thank you", donations));
    }

    public static Result donationsNeedingIndiegogo()
    {
        List<Donation> donations = Donation.find.where()
            .eq("person.organization", Organization.getByHost())
            .eq("indiegogo_reward_given", false).orderBy("date DESC").findList();

        return ok(views.html.donation_list.render("Donations needing Indiegogo reward", donations));
    }

    public static Result donations()
    {
        return ok(views.html.donation_list.render("All donations",
            Donation.find.where()
                .eq("person.organization", Organization.getByHost())
                .orderBy("date DESC").findList()));
    }

    public static int calcAge(Person p) {
        return (int)((new Date().getTime() - p.dob.getTime()) / 1000 / 60 / 60 / 24 / 365.25);
    }

    public static int calcAgeAtBeginningOfSchool(Person p) {
		if (p.dob == null) {
			return -1;
		}
        return (int)((new Date(114, 7, 25).getTime() - p.dob.getTime()) / 1000 / 60 / 60 / 24 / 365.25);
    }

    public static String formatDob(Date d) {
		if (d == null) {
			return "---";
		}
        return new SimpleDateFormat("MM/dd/yy").format(d);
    }

    public static String formatDate(Date d) {
        d = new Date(d.getTime() +
            (Application.getConfiguration().getInt("time_zone_offset") * 1000L * 60 * 60));
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
        TaskList list = TaskList.findById(id);
        List<Person> people = getPeopleForTag(list.tag.id);

        return ok(views.html.task_list.render(list, people));
    }

    public static Result viewMailchimpSettings() {
        MailChimpClient mailChimpClient = new MailChimpClient();
        Organization org = OrgConfig.get().org;

        Map<String, ListMethodResult.Data> mc_list_map =
            Public.getMailChimpLists(mailChimpClient, org.mailchimp_api_key);

        return ok(views.html.view_mailchimp_settings.render(
            Form.form(Organization.class), org,
            MailchimpSync.find.where().eq("tag.organization", OrgConfig.get().org).findList(),
            mc_list_map));
    }

    public static Result saveMailchimpSettings() {
        final Map<String, String[]> values = request().body().asFormUrlEncoded();

        if (values.containsKey("mailchimp_api_key")) {
            OrgConfig.get().org.setMailChimpApiKey(values.get("mailchimp_api_key")[0]);
        }

        if (values.containsKey("mailchimp_updates_email")) {
            OrgConfig.get().org.setMailChimpUpdatesEmail(values.get("mailchimp_updates_email")[0]);
        }

        if (values.containsKey("sync_type")) {
            for (String tag_id : values.get("tag_id")) {
                Tag t = Tag.findById(Integer.parseInt(tag_id));
                MailchimpSync sync = MailchimpSync.create(t,
                    values.get("mailchimp_list_id")[0],
                    values.get("sync_type")[0].equals("local_add"),
                    values.get("sync_type")[0].equals("local_remove"));
            }
        }

        if (values.containsKey("remove_sync_id")) {
            MailchimpSync sync = MailchimpSync.find.byId(Integer.parseInt(
                values.get("remove_sync_id")[0]));
            sync.delete();
        }

        return redirect(routes.CRM.viewMailchimpSettings());
    }
}
