requirejs(['utils'], function(utils) {

    var module = {};

    module.insertIntoSortedList = function(charge, list, parent_el) {
        if (list.length === 0) {
            parent_el.append(charge.el);
            list.push(charge);
            return;
        }

        // Find first element in list that should appear after charge.
        var i = 0;
        for ( ; i<list.length; i++) {
            if (charge.name < list[i].name) {
                break;
            }
        }

        if (i == list.length) {
            list[i-1].el.after(charge.el);
            list.push(charge);
        } else {
            list[i].el.before(charge.el);
            list.splice(i, 0, charge);
        }
    };

    module.indexOfCharge = function(list, charge) {
        for (var i = 0; i < list.length; i++) {
            if (list[i].id == charge.id) {
                return i;
            }
        }

        return -1;
    };

    module.login_message_shown = false;

    module.checkboxChanged = function(charge) {
        charge.checkbox.prop("disabled", true);
        var i = module.indexOfCharge(app.active_rps, charge);

        url = "/setResolutionPlanComplete?id=" + charge.id +
            "&complete=" + (i >= 0);
        $.post(url)
            .done(function(data) {
                // We expect an empty response. If it is not empty, the user
                // probably got redirected to the login page.
                if (data !== "") {
                    if (!login_message_shown) {
                        alert("Your change was not saved. You may not be logged in.");
                    }
                    login_message_shown = true;
                    return;
                }
                charge.el.addClass("rp-moved");
                if (i >= 0) {
                    charge.el.fadeOut("slow", function() {
                        charge.el.detach();
                        app.active_rps.splice(i, 1);
                        module.insertIntoSortedList(charge, app.completed_rps, $(".completed-rps"));
                        charge.el.show();
                    });
                } else {
                    i = module.indexOfCharge(app.completed_rps, charge);
                    charge.el.fadeOut("slow", function() {
                        charge.el.detach();
                        app.completed_rps.splice(i, 1);
                        module.insertIntoSortedList(charge, app.active_rps, $(".active-rps"));
                        charge.el.show();
                    });
                }
            })
            .fail(function() {
            })
            .always(function() {
                charge.checkbox.prop("disabled", false);
            });
    };

    module.Charge = function(data, el) {
        var self = this;

        this.removeCharge = function() {
            self.el.remove();
            $.post("/removeCharge?id=" + self.id);
        };

        self.id = data.id;
        self.el = el;
        self.name = utils.displayName(data.person).toLowerCase();

        self.checkbox = el.find("input");
        self.checkbox.prop("checked", data.rp_complete);

        self.checkbox.change(function() {
            module.checkboxChanged(self);
        });
    };

    module.addCharge = function(data, parent_el) {
        var new_charge_el = parent_el.append(
            app.rp_template({
                "name": utils.displayName(data.person),
                "case_number": data.the_case.case_number,
                "day_of_week": data.dayOfWeek,
                "rule_title": data.rule.title,
                "resolution_plan": data.resolution_plan,
                "sm_decision": data.sm_decision,
                })).children(":last-child");

        return new module.Charge(data, new_charge_el);
    };


    app.rp_template = Handlebars.compile($("#rp-template").html());

    for (var i in app.initial_data.active_rps) {
        module.insertIntoSortedList(
            module.addCharge(app.initial_data.active_rps[i], $(".active-rps")),
            app.active_rps,
            $(".active-rps"));
    }
    for (i in app.initial_data.completed_rps) {
        module.insertIntoSortedList(
            module.addCharge(app.initial_data.completed_rps[i], $(".completed-rps")),
            app.completed_rps,
            $(".completed-rps"));
    }
});

