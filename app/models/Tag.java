package models;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.*;

import play.db.ebean.Model;
import play.db.ebean.Model.Finder;

@Entity
public class Tag extends Model {
    @Id
    public Integer id;

    public String title;

    @OneToOne(mappedBy="tag")
    public TaskList task_list;

    public static Finder<Integer, Tag> find = new Finder(
        Integer.class, Tag.class
    );

    public static Tag create(String title) {
        Tag result = new Tag();
        result.title = title;

        result.save();
        return result;
    }

    public static Map<String, List<Tag>> getWithPrefixes() {
        Map<String, List<Tag>> result = new HashMap<String, List<Tag>>();

        for (Tag t : find.order("title ASC").findList()) {
            String[] splits = t.title.split(":");
            String prefix = t.title;

            if (splits.length > 1) {
                prefix = splits[0];
            }

            List<Tag> cur_list = result.get(splits[0]);
            if (cur_list == null) {
                cur_list = new ArrayList<Tag>();
            }
            cur_list.add(t);
            result.put(splits[0], cur_list);
        }

        return result;
    }
}
