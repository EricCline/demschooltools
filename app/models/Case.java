package models;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.persistence.*;

import play.db.ebean.Model;
import play.db.ebean.Model.Finder;

@Entity
public class Case extends Model {
    @Id
    public String case_number;

    public String findings;

    public Date date;

    @ManyToOne
    @JoinColumn(name="meeting_id")
    public Meeting meeting;

    public static Finder<String, Case> find = new Finder(
        String.class, Case.class
    );
}
