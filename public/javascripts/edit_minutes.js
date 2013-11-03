next_case_num = 1;

function showPersonHistoryInSidebar(person) {
    $("#sidebar").html("<h2>Loading...</h2>");
    $.get("/personHistory/" + person.id,
          null,
           function(data, status, jqXHR) {
            $("#sidebar").html(data);
    });
}

function Person(id, name) {
    this.name = name;
    this.id = id;
    var self = this;

    this.render = function() {
        return app.person_template({"name": name});
    }

    // called by PeopleChooser after self.el has been
    // initialized.
    this.activateClick = function() {
        self.el.click(
            function() {
                showPersonHistoryInSidebar(self);
            }
        );
    }
}

function PeopleChooser(el, on_add, on_remove) {
    this.el = el;
    this.people = [];
    var self = this;

    this.search_box = el.find(".person_search");
    this.search_box.autocomplete({
        source: "/jsonPeople",
    });

    self.one_person_mode = false;

//  el.mouseleave(function() {
//      if (self.people.length > 0) {
//          self.search_box.fadeOut();
//      } } );
//  el.mouseenter(function() { self.search_box.fadeIn(); } );
//
    this.search_box.bind( "autocompleteselect", function(event, ui) {
        new_person = self.addPerson(ui.item.id, ui.item.label);

        if (on_add && new_person) {
            on_add(new_person);
        }

        self.search_box.val('');
        event.preventDefault(); // keep jquery from inserting name into textbox
    });

    this.addPerson = function(id, name) {

        // Don't add people who have already been added.
        for (i in self.people) {
            if (id == self.people[i].id) {
                return;
            }
        }

        if (self.one_person_mode) {
            self.search_box.hide();
            self.el.find(".glyphicon").hide();
        }

        var p = new Person(id, name);
        self.people.push(p);
        p.el = self.el.find(".people").append(p.render()).children(":last-child");
        p.activateClick();

        p.el.find("img").click(function() { self.removePerson(p) });

        return p;
    }

    this.removePerson = function(person) {
        if (on_remove) {
            on_remove(person);
        }

        $(person.el).remove();

        for (i in self.people) {
            if (self.people[i] == person) {
                self.people.splice(i, 1);
            }
        }

        if (self.one_person_mode) {
            self.search_box.show();
            self.el.find(".glyphicon").show();
        }
    }

    this.loadPeople = function(people) {
        for (i in people) {
            self.addPerson(people[i].id, people[i].name);
        }
    }

    this.setOnePersonMode = function(one_person_mode) {
        self.one_person_mode = one_person_mode;
        return self;
    }
}

function RuleChooser(el, on_change) {
    this.el = el;
    var self = this;

    this.search_box = el.find(".rule_search");
    this.search_box.autocomplete({
        source: "/jsonRules",
    });

    this.search_box.bind( "autocompleteselect", function(event, ui) {
        self.setRule(ui.item.id, ui.item.label);

        if (on_change) { on_change(); }

        self.search_box.val('');
        event.preventDefault(); // keep jquery from inserting name into textbox
    });

    this.setRule = function(id, title) {
        self.search_box.hide();

        self.rule = id;
        self.rule_el =
            self.el.prepend(app.rule_template({name: title})).
                children(":first-child");

        self.rule_el.find("img").click(function() { self.unsetRule() });
    }

    this.unsetRule = function() {
        if (on_change) { on_change(); }

        $(self.rule_el).remove();

        self.rule = null;
        self.rule_el = null;

        self.search_box.show();
    }

    this.loadData = function(json) {
        self.setRule(json.id, json.title);
    }
}

function displayName(p) {
	if (p.displayName) {
		return p.displayName;
	} else {
		return p.first_name;
	}
}

function Charge(charge_id, el) {
    var self = this;

    this.loadData = function(json) {
        el.find(".resolution_plan").val(json.resolution_plan);
        if (json.plea == "Guilty") {
            el.find(".plea-guilty").prop("checked", true);
        } else if (json.plea == "Not Guilty") {
            el.find(".plea-not-guilty").prop("checked", true);
        }

        // rule, person
        if (json.person) {
            self.people_chooser.addPerson(
                json.person.person_id,
                displayName(json.person));
        }

        if (json.rule) {
            self.rule_chooser.loadData(json.rule);
        }
    }

    this.saveIfNeeded = function() {
        window.setTimeout(self.saveIfNeeded, 2000);
        if (!self.is_modified) {
            return;
        }

        url = "/saveCharge?id=" + charge_id;

        if (self.people_chooser.people.length > 0) {
            url += "&person_id=" + self.people_chooser.people[0].id;
        }

        url += "&resolution_plan=" + encodeURIComponent(el.find(".resolution_plan").val());
        plea = el.find(":checked");
        if (plea) {
            url += "&plea=" + plea.val();
        }

        if (self.rule_chooser.rule) {
            url += "&rule_id=" + self.rule_chooser.rule;
        }

        self.is_modified = false;
        $.post(url);
    }

    this.removeCharge = function() {
        self.el.remove();
        $.post("/removeCharge?id=" + charge_id);
    }

    this.markAsModified = function() {
        self.is_modified = true;
    }

    self.el = el;
    self.is_modified = false;
    window.setTimeout(self.saveIfNeeded, 2000);

    self.remove_button = el.find("button")
    self.remove_button.click(self.removeCharge);

    el.find(".resolution_plan").change(self.markAsModified);
    el.find(".plea-guilty").change(self.markAsModified);
    el.find(".plea-not-guilty").change(self.markAsModified);

    self.people_chooser = new PeopleChooser(el.find(".people_chooser"),
                                            self.markAsModified,
                                            self.markAsModified);
    self.people_chooser.setOnePersonMode(true);

    self.rule_chooser = new RuleChooser(el.find(".rule_chooser"),
                                        self.markAsModified);

    el.find("input[type=radio]").prop("name", "plea-" + charge_id);

    el.mouseleave(function() { self.remove_button.hide(); } );
    el.mouseenter(function() { self.remove_button.show(); } );
}

function Case (id, el) {
    var self = this;

    this.saveIfNeeded = function() {
        window.setTimeout(self.saveIfNeeded, 2000);
        if (!self.is_modified) {
            return;
        }

        url = "/saveCase?id=" + id;
        if (self.writer_chooser.people.length > 0) {
            url += "&writer_id=" + self.writer_chooser.people[0].id;
        }
        url += "&location=" + encodeURIComponent(self.el.find(".location").val());
        url += "&findings=" + encodeURIComponent(self.el.find(".findings").val());
        url += "&date=" + encodeURIComponent(self.el.find(".date").val());

        self.is_modified = false;
        $.post(url);
    }

    this.markAsModified = function() {
        self.is_modified = true;
    }

    this.loadData = function(data) {
        el.find(".location").val(data.location);
        el.find(".date").val(data.date);
        el.find(".findings").val(data.findings);

        if (data.writer) {
            self.writer_chooser.addPerson(data.writer.person_id,
                  displayName(data.writer));
        }

        for (i in data.testify_records) {
            t_r = data.testify_records[i];
            self.testifier_chooser.addPerson(
                t_r.person.person_id,
                displayName(t_r.person));
        }

        for (i in data.charges) {
            ch = data.charges[i];
            new_charge = self.addChargeNoServer(ch.id);
            new_charge.loadData(ch);
        }
    }

    this.addCharge = function() {
        $('body').animate({'scrollTop': $('body').scrollTop() + 90}, 'slow');
        $.post("/addCharge?case_number=" + id,
               function(data, textStatus, jqXHR) {
            self.addChargeNoServer(parseInt(data))
        } );
    }

    this.addChargeNoServer = function(charge_id) {
        var new_charge_el = self.el.find(".charges").append(
            app.charge_template()).children(":last-child");
        var new_charge = new Charge(charge_id, new_charge_el);
        return new_charge;
    }

    this.id = id
    this.el = el;
    this.writer_chooser = new PeopleChooser(el.find(".writer"),
                                            self.markAsModified,
                                            self.markAsModified);
    this.writer_chooser.setOnePersonMode(true);

    this.testifier_chooser = new PeopleChooser(
        el.find(".testifier"),
        function(person) {
            $.post("/addTestifier?case_number=" + id +
                   "&person_id=" + person.id);
        },
        function(person) {
            $.post("/removeTestifier?case_number=" + id +
                   "&person_id=" + person.id);
        });
    this.is_modified = false;

    el.find(".location").change(self.markAsModified);
    el.find(".findings").change(self.markAsModified);
    el.find(".date").change(self.markAsModified);

    el.find(".add-charges").click(self.addCharge);

    window.setTimeout(self.saveIfNeeded, 2000);
}

function addPersonAtMeeting(person, role) {
    $.post("/addPersonAtMeeting?meeting_id=" + app.meeting_id
           + "&person_id=" + person.id +
           "&role=" + role );
}

function removePersonAtMeeting(person, role) {
    $.post("/removePersonAtMeeting?meeting_id=" + app.meeting_id
           + "&person_id=" + person.id +
           "&role=" + role );
}

function makePeopleChooser(selector, role) {
    return new PeopleChooser(
        $(selector),
        function(person) { addPersonAtMeeting(person, role) },
        function(person) { removePersonAtMeeting(person, role) });
}

function loadInitialData() {
    app.committee_chooser.loadPeople(app.initial_data.committee);
    app.chair_chooser.loadPeople(app.initial_data.chair);
    app.notetaker_chooser.loadPeople(app.initial_data.notetaker);
    app.sub_chooser.loadPeople(app.initial_data.sub);

    for (i in app.initial_data.cases) {
        data = app.initial_data.cases[i];
        new_case = addCaseNoServer(data.case_number);
        new_case.loadData(data);
    }
}

$(function () {
    Handlebars.registerHelper('render', function(partialId, options) {
      var selector = 'script[type="text/x-handlebars-template"]#' + partialId,
          source = $(selector).html(),
          html = Handlebars.compile(source)(options.hash);

      return new Handlebars.SafeString(html);
    });

    $("#meeting").append(Handlebars.compile($("#meeting-template").html())());

    Handlebars.registerPartial("people-chooser", $("#people-chooser").html());
    Handlebars.registerPartial("rule-chooser", $("#rule-chooser").html());

    app.case_template = Handlebars.compile($("#case-template").html());
    app.person_template = Handlebars.compile($("#person-template").html());
    app.charge_template = Handlebars.compile($("#charge-template").html());
    app.rule_template = Handlebars.compile($("#rule-template").html());

    app.committee_chooser =
        makePeopleChooser(".committee", app.ROLE_JC_MEMBER);

    app.chair_chooser =
        makePeopleChooser(".chair", app.ROLE_JC_CHAIR);
    app.chair_chooser.setOnePersonMode(true);

    app.notetaker_chooser =
        makePeopleChooser(".notetaker", app.ROLE_NOTE_TAKER);
    app.sub_chooser =
        makePeopleChooser(".sub", app.ROLE_JC_SUB);

    loadInitialData();
});

function addCaseNoServer(id)
{
    new_case = $("#meeting-cases").append(
        app.case_template({"num": id})).
        children(":last-child");

    var case_obj = new Case(id, new_case);
    app.cases.push(case_obj);

    $("#meeting-cases").append(case_obj.el);

    next_case_num += 1;

    return case_obj;
}

function addCase()
{
    d = new Date();

    case_id = app.case_number_prefix;
    if (next_case_num < 10) {
        case_id += "0";
    }
    case_id += next_case_num;
    $.post("/newCase?id=" + case_id +
           "&meeting_id=" + app.meeting_id, "",
           function(data, textStatus, jqXHR) {
        new_case = addCaseNoServer(case_id);
        $('body').animate({'scrollTop': new_case.el.offset().top + 500}, 'slow');
    });
}

