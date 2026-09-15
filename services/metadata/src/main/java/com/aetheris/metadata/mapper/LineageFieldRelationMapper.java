package com.aetheris.metadata.mapper;
import com.aetheris.metadata.entity.LineageFieldRelation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
@Mapper public interface LineageFieldRelationMapper extends BaseMapper<LineageFieldRelation> {
    @Select("SELECT * FROM metadata.lineage_field_relation "
            + "WHERE source_table = #{table} "
            + "AND source_column = #{column} "
            + "ORDER BY updated_at DESC")
    java.util.List<LineageFieldRelation> selectBySource(@Param("table") String table,
                                                         @Param("column") String column);
}
