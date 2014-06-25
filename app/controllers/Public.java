package controllers;

import com.feth.play.module.pa.PlayAuthenticate;
import com.feth.play.module.pa.user.AuthUser;

import models.*;

import play.*;
import play.data.*;
import play.libs.Json;
import play.mvc.*;
import play.mvc.Http.Context;

import views.html.*;

public class Public extends Controller {

	public static Result postEmail() {
		final java.util.Map<String, String[]> values = request().body().asFormUrlEncoded();

		Email.create(values.get("email")[0]);

        return ok();
	}

    public static Result oAuthDenied(String provider)
    {
        session().remove("timeout");
        return redirect(routes.Public.index());
    }

    public static Result index()
    {
        String u = new Secured().getUsername(ctx(), false);
        if (u != null) {
            return redirect(routes.Application.index());
        }
        return ok(views.html.login.render(flash("notice")));
    }

    public static Result authenticate(String provider) {
        session("timeout", "" + System.currentTimeMillis());
        return com.feth.play.module.pa.controllers.Authenticate.authenticate(provider);
    }
}
