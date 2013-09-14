#!/usr/bin/python
import psycopg2
import sys
import pprint
import re


regSpaces = re.compile('\s+')
def Point(geoStr):
	coords = regSpaces.split(geoStr.strip())
	return [coords[0],coords[1]]

def LineString(geoStr):
	points = geoStr.strip().split(',')
	points = map(Point,points)
	return points

def esc(s):
	return s.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("&", "&amp;").replace("'","&apos;")

def process_polygons(tags, filename):
	conn_string = "host='127.0.0.1' dbname='osm' user='osm' password='osm' port='5433'"
	f = open(filename,'w')
	f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
	f.write('<osm version="0.5">\n')
 
	conn = psycopg2.connect(conn_string)
	cursor = conn.cursor()
	shift = 2
	array = ['name']
	queryFields = ", name"
	conditions = " 1=0"
	for tag in tags:
		if tag == "natural" :
			queryFields += ", \"natural\""
			conditions += " or (\"natural\" <> '' and \"natural\" <> 'water')"
			array.append(tag)
		elif tag == "lake" :
			array.append("natural")
			queryFields += ", \"natural\""
			conditions += " or \"natural\" = 'water'"
		else :
			array.append(tag)
			queryFields += ", " + tag
			conditions += " or "+tag+" <> ''"

	cursor.execute("select osm_id, ST_AsText(ST_Transform(ST_Simplify(way,500),4326)) " + queryFields +
				   " from planet_osm_polygon where way_area > 10000000"
				   " and ("+conditions+") "
				  # "LIMIT 1000"
				   ";")
 
	# retrieve the records from the database
	parenComma = re.compile('\)\s*,\s*\(')
	trimParens = re.compile('^\s*\(?(.*?)\)?\s*$')
	rel_id = -1
	way_id = -100000000
	node_id =-10000000000000

	for row in cursor:
		if row[1] is None:
			continue
		node_xml = ""
		way_xml = ""
		rel_id = rel_id - 1
		xml = '\n<relation id="%s" >\n' % (rel_id)
		xml += '\t<tag k="type" v="multipolygon" />\n'
		base = shift
		while base - shift < len(array):
			if row[base] is not None:
				xml += '\t<tag k="%s" v="%s" />\n' % (array[base - shift], esc(row[base]))
			base = base + 1


		coordinates = row[1][len("POLYGON("):-1]
		rings = parenComma.split(coordinates)
		first = 0
		for i,ring in enumerate(rings):
			ring = trimParens.match(ring).groups()[0]
			line = LineString(ring)
			way_id = way_id - 1;
			if first == 0:
				xml += '\t<member type="way" ref="%s" role="outer" />\n' % (way_id)
				first = 1
			else:
				xml += '\t<member type="way" ref="%s" role="inner" />\n' % (way_id)

			way_xml += '\n<way id="%s" >\n' % (way_id)
			first_node_id = 0
			first_node = []
			for c in line:
				node_id = node_id - 1
				nid = node_id
				if first_node_id == 0:
					first_node_id = node_id
					first_node = c
					node_xml += '\n<node id="%s" lat="%s" lon="%s"/>' % (nid, c[1], c[0])
				elif first_node == c:
					nid = first_node_id
				else:
					node_xml += '\n<node id="%s" lat="%s" lon="%s"/>' % (nid, c[1], c[0])
				way_xml += '\t<nd ref="%s" />\n' % (nid)
			way_xml += '</way>'
		xml += '</relation>'	
		f.write(node_xml)
		f.write(way_xml)
		f.write(xml)
		f.write('\n')
	f.write('</osm>')

if __name__ == "__main__":
		process_polygons(['landuse', 'natural', 'historic','leisure'], 'polygon_natural_landuse.osm')
		process_polygons(['lake'], 'polygon_lake_water.osm')
		process_polygons(['aeroway', 'military', 'power', 'tourism'], 'polygon_aeroway_military_tourism.osm')
